package com.scenelog.analytics.batch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * batch 프로파일에서만 스케줄러가 살아난다 (기본 프로파일 쪽 단언은 ScenelogApplicationTests).
 * initial-delay를 1시간으로 줘 테스트 중 실제 집계는 돌지 않는다.
 */
@SpringBootTest(properties = "scenelog.batch.aggregate.initial-delay=PT1H")
@ActiveProfiles("batch")
class BatchProfileWiringTest {

    @Autowired ApplicationContext ctx;

    @Test
    void batch_프로파일에서는_정기_집계_태스크가_1개_등록된다() {
        assertThat(ctx.getBeanNamesForType(AggregationScheduler.class)).hasSize(1);
        var registrar = ctx.getBean(ScheduledAnnotationBeanPostProcessor.class);
        assertThat(registrar.getScheduledTasks()).hasSize(1);
    }
}
