package com.flashmart.notification.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class NotificationLogPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    private NotificationLog pending(long orderId, RecipientType recipientType) {
        NotificationLog log = new NotificationLog();
        log.setOrderId(orderId);
        log.setEventType("PaymentConfirmed");
        log.setRecipientType(recipientType);
        log.setChannel(NotificationChannel.EMAIL);
        log.setStatus(NotificationStatus.PENDING);
        return log;
    }

    private NotificationLog sent(long orderId, RecipientType recipientType, String address) {
        NotificationLog log = pending(orderId, recipientType);
        log.setStatus(NotificationStatus.SENT);
        log.setRecipientAddress(address);
        log.setSentAt(Instant.now());
        return log;
    }

    private void insertRaw(String column, String value) {
        entityManager.createNativeQuery(
                        "insert into notification_log (order_id, event_type, recipient_type, channel, status) "
                                + "values (1, " + ("event_type".equals(column) ? ":v" : "'PaymentConfirmed'") + ", "
                                + ("recipient_type".equals(column) ? ":v" : "'BUYER'") + ", "
                                + ("channel".equals(column) ? ":v" : "'EMAIL'") + ", "
                                + ("status".equals(column) ? ":v" : "'PENDING'") + ")")
                .setParameter("v", value)
                .executeUpdate();
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        NotificationLog log = sent(42L, RecipientType.BUYER, "priya@example.com");
        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        NotificationLog loaded = entityManager.find(NotificationLog.class, log.getId());

        assertNotNull(loaded);
        assertEquals(42L, loaded.getOrderId());
        assertEquals("PaymentConfirmed", loaded.getEventType());
        assertEquals(RecipientType.BUYER, loaded.getRecipientType());
        assertEquals(NotificationChannel.EMAIL, loaded.getChannel());
        assertEquals(NotificationStatus.SENT, loaded.getStatus());
        assertEquals("priya@example.com", loaded.getRecipientAddress());
        assertNotNull(loaded.getSentAt());
        assertEquals(0, loaded.getAttempts());
    }

    @Test
    void enumsAreStoredAsTextNotAsNumbers() {
        NotificationLog log = sent(42L, RecipientType.BRAND, "contact@puma.com");
        entityManager.persist(log);
        entityManager.flush();

        Object[] raw = (Object[]) entityManager.createNativeQuery(
                        "select recipient_type, channel, status from notification_log where id = :id")
                .setParameter("id", log.getId())
                .getSingleResult();

        assertEquals("BRAND", raw[0]);
        assertEquals("EMAIL", raw[1]);
        assertEquals("SENT", raw[2]);
    }

    @Test
    void theSameEventForTheSameRecipientCannotBeLoggedTwice() {
        entityManager.persist(sent(42L, RecipientType.BUYER, "priya@example.com"));
        entityManager.flush();

        NotificationLog redelivered = sent(42L, RecipientType.BUYER, "priya@example.com");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(redelivered);
            entityManager.flush();
        });
    }

    @Test
    void oneEventStillReachesBothTheBuyerAndTheBrand() {
        entityManager.persist(sent(42L, RecipientType.BUYER, "priya@example.com"));
        entityManager.persist(sent(42L, RecipientType.BRAND, "contact@puma.com"));
        entityManager.flush();
    }

    @Test
    void theAddressIsKeptEvenAfterTheContactWouldHaveChanged() {
        NotificationLog log = sent(42L, RecipientType.BUYER, "old@example.com");
        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        NotificationLog loaded = entityManager.find(NotificationLog.class, log.getId());
        assertEquals("old@example.com", loaded.getRecipientAddress(),
                "the log must say where the message actually went, not where it would go now");
    }

    @Test
    void aFailedSendRecordsItsReasonAndKeepsNoSentAt() {
        NotificationLog log = pending(42L, RecipientType.BUYER);
        log.setStatus(NotificationStatus.FAILED);
        log.setAttempts(1);
        log.setLastError("no contact cached for buyer 7");
        log.setNextRetryAt(Instant.now().plusSeconds(60));
        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        NotificationLog loaded = entityManager.find(NotificationLog.class, log.getId());
        assertEquals(NotificationStatus.FAILED, loaded.getStatus());
        assertEquals("no contact cached for buyer 7", loaded.getLastError());
        assertNull(loaded.getSentAt());
        assertNull(loaded.getRecipientAddress(), "a send that never happened has no address");
    }

    @Test
    void aSentRowMustCarryASentAt() {
        NotificationLog log = pending(42L, RecipientType.BUYER);
        log.setStatus(NotificationStatus.SENT);
        log.setRecipientAddress("priya@example.com");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(log);
            entityManager.flush();
        });
    }

    @Test
    void anUnsentRowMustNotCarryASentAt() {
        NotificationLog log = pending(42L, RecipientType.BUYER);
        log.setSentAt(Instant.now());

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(log);
            entityManager.flush();
        });
    }

    @Test
    void aSentRowMustSayWhereItWent() {
        NotificationLog log = pending(42L, RecipientType.BUYER);
        log.setStatus(NotificationStatus.SENT);
        log.setSentAt(Instant.now());

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(log);
            entityManager.flush();
        });
    }

    @Test
    void databaseRejectsAnUnknownEventType() {
        assertThrows(PersistenceException.class, () -> insertRaw("event_type", "PaymentCaptured"));
    }

    @Test
    void databaseRejectsAnUnknownRecipientType() {
        assertThrows(PersistenceException.class, () -> insertRaw("recipient_type", "ADMIN"));
    }

    @Test
    void databaseRejectsAnUnknownChannel() {
        assertThrows(PersistenceException.class, () -> insertRaw("channel", "SMS"));
    }

    @Test
    void databaseRejectsAnUnknownStatus() {
        assertThrows(PersistenceException.class, () -> insertRaw("status", "DELIVERED"));
    }

    @Test
    void databaseRejectsNegativeAttempts() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into notification_log (order_id, event_type, recipient_type, channel, status, attempts) "
                                + "values (1, 'PaymentConfirmed', 'BUYER', 'EMAIL', 'PENDING', -1)")
                .executeUpdate());
    }
}
