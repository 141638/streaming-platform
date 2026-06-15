package com.streaming.auth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("PMD.GuardLogStatement") // ignore guard log
public class MailCommonService {
    /** mail template content type */
    private static final String CONTENT_TYPE = "text/html; charset=UTF-8";
    /** mail template encoding type */
    private static final String ENCODING = "UTF-8";
    /** log message for start sending the mail */
    private static final String MAIL_LOG_SENDING = "[MailCommonService.sendEmails] Begin sending emails";

    /** Java mail sender */
    private final JavaMailSender mailSender;

    /** configured mail host */
    @Value("${spring.mail.host}")
    private String mailHost;

    /** configured mail port */
    @Value("${spring.mail.port}")
    private Integer mailPort;

    /**
     * Send mail
     *
     * @param to receiver's mail address
     * @param subject mail subject
     * @param cc cc to
     * @param templateHtml mail content in HTML template
     * @throws MessagingException error thrown when helper method meets any error
     */
    @Async
    public void send(String to, String subject, List<String> cc, String templateHtml) throws MessagingException {
        log.info("[MailCommonService.send] begin send mail");
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, ENCODING);

        log.info("[MailCommonService.send] send mail to: {}", to);
        helper.setTo(to);
        helper.setSubject(subject);
        if (!cc.isEmpty()) {
            helper.setCc(subject);
        }
        message.setContent(templateHtml, CONTENT_TYPE);

        mailSender.send(message);
        log.info("[MailCommonService.send] send mail success");
    }
}
