/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.rpc.CheckVerificationCodeErrorType;
import org.signal.registration.rpc.CheckVerificationCodeRequest;
import org.signal.registration.rpc.CheckVerificationCodeResponse;
import org.signal.registration.rpc.CreateRegistrationSessionErrorType;
import org.signal.registration.rpc.CreateRegistrationSessionRequest;
import org.signal.registration.rpc.CreateRegistrationSessionResponse;
import org.signal.registration.rpc.GetRegistrationSessionMetadataErrorType;
import org.signal.registration.rpc.GetRegistrationSessionMetadataRequest;
import org.signal.registration.rpc.GetRegistrationSessionMetadataResponse;
import org.signal.registration.rpc.MessageTransport;
import org.signal.registration.rpc.RegistrationServiceGrpc;
import org.signal.registration.rpc.SendVerificationCodeErrorType;
import org.signal.registration.rpc.SendVerificationCodeRequest;
import org.signal.registration.rpc.SendVerificationCodeResponse;
import org.signal.registration.sender.IllegalSenderArgumentException;
import org.signal.registration.sender.LastDigitsOfPhoneNumberVerificationCodeSender;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.util.UUIDUtil;

@MicronautTest
public class IntegrationTest {

  @MockBean
  SenderSelectionStrategy senderSelectionStrategy() {
    final SenderSelectionStrategy senderSelectionStrategy = mock(SenderSelectionStrategy.class);
    when(senderSelectionStrategy.chooseVerificationCodeSender(any(), any(), any(), any(), any()))
        .thenReturn(new LastDigitsOfPhoneNumberVerificationCodeSender());

    return senderSelectionStrategy;
  }

  @MockBean(named = "session-creation")
  @Named("session-creation")
  RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter() {
    @SuppressWarnings("unchecked") final RateLimiter<Phonenumber.PhoneNumber> rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    return rateLimiter;
  }

  @Inject
  private RegistrationServiceGrpc.RegistrationServiceBlockingStub blockingStub;

  @Inject
  private RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter;

  @Inject
  private SenderSelectionStrategy senderSelectionStrategy;

  @Test
  void register() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    assertFalse(sendVerificationCodeResponse.hasError());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(sendVerificationCodeResponse.getSessionId())
            .setVerificationCode(LastDigitsOfPhoneNumberVerificationCodeSender.getVerificationCode(phoneNumber))
            .build());

    assertTrue(checkVerificationCodeResponse.getVerified());
  }

  @Test
  void registerIncorrectCode() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(sendVerificationCodeResponse.getSessionId())
            .setVerificationCode("incorrect")
            .build());

    assertFalse(checkVerificationCodeResponse.getVerified());
  }

  @Test
  void createSessionRateLimited() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");
    final Duration retryAfter = Duration.ofSeconds(60);

    when(sessionCreationRateLimiter.checkRateLimit(phoneNumber))
        .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(retryAfter)));

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.ERROR,
        createRegistrationSessionResponse.getResponseCase());

    assertTrue(createRegistrationSessionResponse.getError().getMayRetry());
    assertEquals(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_RATE_LIMITED,
        createRegistrationSessionResponse.getError().getErrorType());

    assertEquals(retryAfter.getSeconds(), createRegistrationSessionResponse.getError().getRetryAfterSeconds());
  }

  @Test
  void createSessionIllegalPhoneNumber() {
    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(0L)
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.ERROR,
        createRegistrationSessionResponse.getResponseCase());

    assertEquals(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_ILLEGAL_PHONE_NUMBER,
        createRegistrationSessionResponse.getError().getErrorType());

    assertFalse(createRegistrationSessionResponse.getError().getMayRetry());
  }

  @Test
  void getSessionMetadata() throws NumberParseException {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final GetRegistrationSessionMetadataResponse getSessionMetadataResponse =
        blockingStub.getSessionMetadata(GetRegistrationSessionMetadataRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .build());

    assertEquals(GetRegistrationSessionMetadataResponse.ResponseCase.SESSION_METADATA,
        getSessionMetadataResponse.getResponseCase());

    assertEquals(createRegistrationSessionResponse.getSessionMetadata(),
        getSessionMetadataResponse.getSessionMetadata());

    assertEquals(phoneNumber,
        PhoneNumberUtil.getInstance().parse("+" + getSessionMetadataResponse.getSessionMetadata().getE164(), null));
  }

  @Test
  void getSessionMetadataNotFound() {
    final GetRegistrationSessionMetadataResponse getSessionMetadataResponse =
        blockingStub.getSessionMetadata(GetRegistrationSessionMetadataRequest.newBuilder()
            .setSessionId(UUIDUtil.uuidToByteString(UUID.randomUUID()))
            .build());

    assertEquals(GetRegistrationSessionMetadataResponse.ResponseCase.ERROR,
        getSessionMetadataResponse.getResponseCase());

    assertEquals(GetRegistrationSessionMetadataErrorType.GET_REGISTRATION_SESSION_METADATA_ERROR_TYPE_NOT_FOUND,
        getSessionMetadataResponse.getError().getErrorType());
  }

  @Test
  void sendVerificationCodeNoSession() {
    @SuppressWarnings("ResultOfMethodCallIgnored") final StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class,
            () -> blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
                .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .build()));

    assertEquals(Status.INVALID_ARGUMENT, exception.getStatus());
  }

  @Test
  void sendVerificationCodeAlreadyVerified() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(sendVerificationCodeResponse.getSessionId())
            .setVerificationCode(LastDigitsOfPhoneNumberVerificationCodeSender.getVerificationCode(phoneNumber))
            .build());

    assertTrue(checkVerificationCodeResponse.getVerified());

    final SendVerificationCodeResponse sendVerificationCodeAfterVerificationResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    assertTrue(sendVerificationCodeAfterVerificationResponse.hasError());
    assertFalse(sendVerificationCodeAfterVerificationResponse.getError().getMayRetry());
    assertTrue(sendVerificationCodeAfterVerificationResponse.hasSessionMetadata());

    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_ALREADY_VERIFIED,
        sendVerificationCodeAfterVerificationResponse.getError().getErrorType());
  }

  @Test
  void testSendVerificationCodeSenderRejected() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final VerificationCodeSender rejectRequestsSender = mock(VerificationCodeSender.class);
    when(rejectRequestsSender.sendVerificationCode(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new SenderRejectedRequestException("Test")));

    when(senderSelectionStrategy.chooseVerificationCodeSender(
        eq(org.signal.registration.sender.MessageTransport.SMS), eq(phoneNumber), any(), any(), any()))
        .thenReturn(rejectRequestsSender);

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    assertTrue(sendVerificationCodeResponse.hasError());
    assertFalse(sendVerificationCodeResponse.getError().getMayRetry());

    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SENDER_REJECTED,
        sendVerificationCodeResponse.getError().getErrorType());
  }

  @Test
  void testSendVerificationCodeIllegalSenderArgument() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final VerificationCodeSender illegalArgumentSender = mock(VerificationCodeSender.class);
    when(illegalArgumentSender.sendVerificationCode(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalSenderArgumentException(new IllegalArgumentException())));

    when(senderSelectionStrategy.chooseVerificationCodeSender(
        eq(org.signal.registration.sender.MessageTransport.SMS), eq(phoneNumber), any(), any(), any()))
        .thenReturn(illegalArgumentSender);

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    assertTrue(sendVerificationCodeResponse.hasError());
    assertFalse(sendVerificationCodeResponse.getError().getMayRetry());

    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SENDER_ILLEGAL_ARGUMENT,
        sendVerificationCodeResponse.getError().getErrorType());
  }

  @Test
  void checkVerificationCodeNoSession() {
    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(ByteString.copyFrom(new byte[16]))
            .setVerificationCode("550123")
            .build());

    assertFalse(checkVerificationCodeResponse.getVerified());
  }

  @Test
  void checkVerificationCodeNoCodeSent() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setVerificationCode(LastDigitsOfPhoneNumberVerificationCodeSender.getVerificationCode(phoneNumber))
            .build());

    assertTrue(checkVerificationCodeResponse.hasError());
    assertFalse(checkVerificationCodeResponse.getError().getMayRetry());
    assertTrue(checkVerificationCodeResponse.hasSessionMetadata());

    assertEquals(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_NO_CODE_SENT,
        checkVerificationCodeResponse.getError().getErrorType());
  }

  private static long phoneNumberToLong(final Phonenumber.PhoneNumber phoneNumber) {
    final String e164 = PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
    return Long.parseLong(e164, 1, e164.length(), 10);
  }
}
