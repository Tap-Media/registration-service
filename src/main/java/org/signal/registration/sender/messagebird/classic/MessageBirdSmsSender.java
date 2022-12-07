package org.signal.registration.sender.messagebird.classic;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.InvalidProtocolBufferException;
import com.messagebird.MessageBirdClient;
import com.messagebird.exceptions.MessageBirdException;
import com.messagebird.objects.Message;
import com.messagebird.objects.MessageResponse;
import io.micrometer.core.instrument.Timer;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeGenerator;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.sender.VerificationSmsBodyProvider;
import org.signal.registration.sender.messagebird.MessageBirdClassicSessionData;
import org.signal.registration.sender.messagebird.MessageBirdErrorCodeExtractor;
import org.signal.registration.sender.messagebird.SenderIdSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends messages through the MessageBird SMS API
 * <p>
 * The <a href="https://developers.messagebird.com/api/sms-messaging/#sms-api">MessageBird SMS API</a> sends arbitrary
 * SMS messages. Verification codes are generated by this class and added to the message text, then later verified
 * through the previously stored session.
 */
@Singleton
public class MessageBirdSmsSender implements VerificationCodeSender {

  private static final Logger logger = LoggerFactory.getLogger(MessageBirdSmsSender.class);

  private final MessageBirdSmsConfiguration configuration;
  private final Executor executor;
  private final VerificationCodeGenerator verificationCodeGenerator;
  private final VerificationSmsBodyProvider verificationSmsBodyProvider;
  private final MessageBirdClient client;
  private final ApiClientInstrumenter apiClientInstrumenter;
  private final SenderIdSelector senderIdSelector;

  public MessageBirdSmsSender(
      final @Named(TaskExecutors.IO) Executor executor,
      final MessageBirdSmsConfiguration configuration,
      final VerificationCodeGenerator verificationCodeGenerator,
      final VerificationSmsBodyProvider verificationSmsBodyProvider,
      final MessageBirdClient messageBirdClient,
      final ApiClientInstrumenter apiClientInstrumenter,
      final SenderIdSelector senderIdSelector) {
    this.configuration = configuration;
    this.executor = executor;
    this.verificationCodeGenerator = verificationCodeGenerator;
    this.verificationSmsBodyProvider = verificationSmsBodyProvider;
    this.client = messageBirdClient;
    this.apiClientInstrumenter = apiClientInstrumenter;
    this.senderIdSelector = senderIdSelector;
  }

  @Override
  public String getName() {
    return "messagebird-sms";
  }

  @Override
  public Duration getSessionTtl() {
    return this.configuration.sessionTtl();
  }

  @Override
  public boolean supportsDestination(final MessageTransport messageTransport, final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges, final ClientType clientType) {
    return messageTransport == MessageTransport.SMS
        && Locale.lookupTag(languageRanges, verificationSmsBodyProvider.getSupportedLanguages()) != null;
  }

  @Override
  public CompletableFuture<byte[]> sendVerificationCode(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber, final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws UnsupportedMessageTransportException {
    final String e164 = PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);

    final String verificationCode = verificationCodeGenerator.generateVerificationCode();
    final String body = this.verificationSmsBodyProvider.getVerificationSmsBody(phoneNumber, clientType,
        verificationCode, languageRanges);
    final Message message = new Message(senderIdSelector.getSenderId(phoneNumber), body, e164);
    message.setDatacoding(DataCodingType.auto);
    message.setValidity((int) getSessionTtl().toSeconds());

    final Timer.Sample sample = Timer.start();

    return CompletableFuture.supplyAsync(() -> {
          try {
            final MessageResponse messageResponse = this.client.sendMessage(message);
            logger.debug("Sent {}, {}, {}", messageResponse, messageResponse.getRecipients(),
                messageResponse.getRecipients().getTotalDeliveryFailedCount());
            if (messageResponse.getRecipients().getTotalDeliveryFailedCount() != 0) {
              throw new CompletionException(new IOException("Failed to deliver message"));
            }
            return MessageBirdClassicSessionData.newBuilder().setVerificationCode(verificationCode).build().toByteArray();
          } catch (MessageBirdException e) {
            throw new CompletionException(e);
          }
        }, this.executor)
        .whenComplete((ignored, throwable) ->
            apiClientInstrumenter.recordApiCallMetrics(
                getName(),
                "sms.create",
                throwable == null,
                MessageBirdErrorCodeExtractor.extract(throwable),
                sample));
  }

  @Override
  public CompletableFuture<Boolean> checkVerificationCode(final String verificationCode, final byte[] sessionData) {
    try {
      final String storedVerificationCode = MessageBirdClassicSessionData.parseFrom(sessionData).getVerificationCode();
      return CompletableFuture.completedFuture(StringUtils.equals(verificationCode, storedVerificationCode));
    } catch (final InvalidProtocolBufferException e) {
      logger.error("Failed to parse stored session data", e);
      return CompletableFuture.failedFuture(e);
    }

  }
}
