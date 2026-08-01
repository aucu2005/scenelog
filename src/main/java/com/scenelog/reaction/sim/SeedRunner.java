package com.scenelog.reaction.sim;

import com.scenelog.auth.User;
import com.scenelog.auth.UserRepository;
import com.scenelog.content.Content;
import com.scenelog.content.ContentRepository;
import com.scenelog.content.WatchSession;
import com.scenelog.content.WatchSessionRepository;
import com.scenelog.reaction.ReactionEvent;
import com.scenelog.reaction.ReactionEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 성능 측정용 대량 시드 (day5, 기획서 §11 정량 성과 1).
 *
 * <p><b>시연 모드({@link SimulateService})와의 차이</b>: 시연은 실제 API 경로(검증 5종)를 통과시키는 것이
 * 목적이라 100만 건이면 수 시간이 걸린다. 시드는 측정용 데이터를 만드는 것이 목적이므로
 * {@code MongoTemplate.bulkOps(UNORDERED)}로 직접 적재한다.
 * <b>이 경로는 API 검증을 우회한다 — 의도된 트레이드오프이며 README에 명시한다.</b>
 *
 * <p>우회하되 §5.4 정합성 원칙(고아 이벤트 금지)은 지킨다: 시청 세션을 PostgreSQL에
 * 실제로 만들고 그 sessionId를 이벤트에 넣는다. 시드 후에도 고아 검출 배치는 0건이어야 정상.
 *
 * <p>멱등: clientEventId가 {@code seed-c{contentId}-u{u}-{seq}}로 결정적이라
 * 유니크 인덱스가 재적재를 막고, 이미 이벤트가 있는 콘텐츠는 통째로 건너뛴다
 * (세션 행이 재실행마다 불어나는 것도 함께 막는다).
 *
 * <p>{@code --spring.profiles.active=seed}로만 실행된다 — 평소엔 이 빈 자체가 없다.
 */
@Component
@Profile("seed")
@RequiredArgsConstructor
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    /** 콘텐츠 2~51 — contentId=1에는 시연 데이터(sim-) 1,883건이 이미 있어 피한다 */
    static final long CONTENT_FROM = 2;
    static final int CONTENT_COUNT = 50;
    static final int USERS_PER_CONTENT = 200;
    /** 세션당 목표 기본 건수 — 50편 × 200세션 × ~100건 ≈ 100만 건 */
    static final int TARGET_PER_SESSION = 100;
    private static final int BULK_BATCH_SIZE = 5_000;
    private static final String OWNER_EMAIL = "tester@scenelog.dev";

    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final WatchSessionRepository sessionRepository;
    private final ReactionEventRepository reactionEventRepository;
    private final MongoTemplate mongoTemplate;

    private final ReactionSimulator simulator = new ReactionSimulator();

    /**
     * 상영시간과 무관하게 세션당 기본 ~{@value TARGET_PER_SESSION}건이 나오도록 baseline을 역산한다.
     * (기대 건수 = durationSec × baseline/60 이므로 baseline = 목표 × 60 / durationSec.
     * 피크 가산으로 실제로는 ~20% 더 나온다 — 총량 계산엔 그만큼 여유가 된다.)
     */
    static Scenario seedScenario(long contentId, int durationSec) {
        double baselinePerMinute = TARGET_PER_SESSION * 60.0 / durationSec;
        return new Scenario(contentId, durationSec, baselinePerMinute,
                Scenario.defaultFor(contentId, durationSec).peaks());
    }

    /** 시뮬레이터 출력(합성 세션·sim- 프리픽스)을 시드용(실제 세션·seed- 프리픽스)으로 변환 */
    static ReactionEvent toSeedEvent(ReactionEvent e, long realSessionId, long ownerUserId, Instant now) {
        return new ReactionEvent(
                e.clientEventId().replaceFirst("^sim-", "seed-"),
                realSessionId, e.contentId(), ownerUserId, e.offsetSec(), e.reactionType(), now);
    }

    @Override
    public void run(ApplicationArguments args) {
        User owner = userRepository.findByEmail(OWNER_EMAIL)
                .orElseThrow(() -> new IllegalStateException(
                        "시드 소유 계정이 없습니다: " + OWNER_EMAIL + " — 회원가입부터 실행하세요"));

        long startedAt = System.currentTimeMillis();
        long totalInserted = 0;
        int seededContents = 0;

        for (long contentId = CONTENT_FROM; contentId < CONTENT_FROM + CONTENT_COUNT; contentId++) {
            Content content = contentRepository.findById(contentId).orElse(null);
            if (content == null) {
                log.warn("contentId={} 없음 — 건너뜀", contentId);
                continue;
            }
            long existing = reactionEventRepository.countByContentId(contentId);
            if (existing > 0) {
                log.info("contentId={} 이미 이벤트 {}건 존재 — 건너뜀 (멱등)", contentId, existing);
                continue;
            }

            totalInserted += seedOneContent(owner, content);
            seededContents++;
            log.info("contentId={} 적재 완료 — 누적 {}건", contentId, totalInserted);
        }

        log.info("시드 완료 — 콘텐츠 {}편, 이벤트 {}건, 소요 {}초. 앱을 종료해도 됩니다.",
                seededContents, totalInserted, (System.currentTimeMillis() - startedAt) / 1000);
    }

    private long seedOneContent(User owner, Content content) {
        int duration = (content.getDurationSec() != null)
                ? content.getDurationSec() : SimulateService.FALLBACK_DURATION_SEC;
        long contentId = content.getContentId();

        // 세션을 PostgreSQL에 실제 생성 — 이벤트가 가리킬 곳이 진짜로 있어야 한다 (§5.4)
        List<WatchSession> sessions = sessionRepository.saveAll(
                java.util.stream.IntStream.rangeClosed(1, USERS_PER_CONTENT)
                        .mapToObj(u -> WatchSession.builder().user(owner).content(content).build())
                        .toList());

        // 합성 사용자 u(1..N)가 리스트 인덱스 u-1의 세션에 대응한다
        List<ReactionEvent> simulated = simulator.generate(
                seedScenario(contentId, duration), USERS_PER_CONTENT, contentId);

        Instant now = Instant.now();
        long inserted = 0;
        List<ReactionEvent> batch = new ArrayList<>(BULK_BATCH_SIZE);
        for (ReactionEvent e : simulated) {
            long realSessionId = sessions.get((int) e.userId() - 1).getSessionId();
            batch.add(toSeedEvent(e, realSessionId, owner.getUserId(), now));
            if (batch.size() == BULK_BATCH_SIZE) {
                inserted += bulkInsert(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) inserted += bulkInsert(batch);
        return inserted;
    }

    /** UNORDERED 벌크 삽입 — 중복 키(11000)는 멱등의 정상 동작이므로 건수만 세고 넘어간다 */
    private int bulkInsert(List<ReactionEvent> batch) {
        BulkOperations ops = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, ReactionEvent.class);
        ops.insert(batch);
        try {
            return ops.execute().getInsertedCount();
        } catch (BulkOperationException ex) {
            long duplicates = ex.getErrors().stream().filter(e -> e.getCode() == 11000).count();
            if (duplicates < ex.getErrors().size()) throw ex;   // 중복 외 오류는 삼키면 안 된다
            return batch.size() - (int) duplicates;
        }
    }
}
