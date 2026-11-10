package com.shg.trip.shgtrip.domain.tripchat.repository;

import com.shg.trip.shgtrip.domain.tripchat.entity.TripChatSession;
import com.shg.trip.shgtrip.domain.tripchat.entity.TripChatStatus;
import com.shg.trip.shgtrip.domain.user.entity.User;
import com.shg.trip.shgtrip.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class TripChatSessionRepositoryIntegrationTest {

    @MockBean S3Client s3Client;

    @Autowired private TripChatSessionRepository repository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate tx;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(User.builder().email("tripchat-it@invalid").nickname(null).build()).getId();
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> repository.deleteByUserId(userId));
        userRepository.deleteById(userId);
    }

    private TripChatSession newSession(String clientRequestId) {
        return TripChatSession.start(userId, clientRequestId, "{\"schemaVersion\":1}", OffsetDateTime.now().plusDays(14));
    }

    @Test
    @DisplayName("사용자당 진행 중 대화는 하나만 남고, 보관한 뒤에는 새로 만들 수 있다")
    void oneActiveSessionPerUser() {
        TripChatSession first = repository.saveAndFlush(newSession("a"));

        assertThatThrownBy(() -> repository.saveAndFlush(newSession("b")))
                .isInstanceOf(DataIntegrityViolationException.class);

        tx.executeWithoutResult(s -> repository.findById(first.getId()).orElseThrow().archive());
        TripChatSession second = repository.saveAndFlush(newSession("c"));
        assertThat(repository.findActive(userId)).map(TripChatSession::getId).contains(second.getId());
    }

    @Test
    @DisplayName("늦게 저장하는 쪽은 낙관적 잠금으로 실패한다")
    void optimisticLock() {
        UUID id = repository.saveAndFlush(newSession("a")).getId();
        TripChatSession stale = repository.findById(id).orElseThrow();

        tx.executeWithoutResult(s -> repository.findById(id).orElseThrow()
                .update(TripChatStatus.REVIEW, "{\"schemaVersion\":1,\"seq\":1}", OffsetDateTime.now().plusDays(14)));

        stale.update(TripChatStatus.ASKING, "{\"schemaVersion\":1,\"seq\":2}", OffsetDateTime.now().plusDays(14));
        assertThatThrownBy(() -> repository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("만료·보관된 세션을 지운다")
    void purgesExpired() {
        TripChatSession expired = TripChatSession.start(userId, "x", "{}", OffsetDateTime.now().minusMinutes(1));
        repository.saveAndFlush(expired);

        int deleted = tx.execute(s -> repository.deleteExpired(OffsetDateTime.now(), OffsetDateTime.now().minusDays(1)));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findById(expired.getId())).isEmpty();
    }
}
