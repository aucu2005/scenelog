package com.scenelog;

import com.scenelog.analytics.batch.AggregationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ScenelogApplicationTests {

	@Autowired ApplicationContext ctx;

	@Test
	void contextLoads() {
	}

	/** 기본 프로파일(데모·RAM 1GB 서버)에는 정기 집계가 없어야 한다 — batch 프로파일 전용 (BatchProfileWiringTest) */
	@Test
	void 기본_프로파일에는_정기_집계_스케줄러가_없다() {
		assertThat(ctx.getBeanNamesForType(AggregationScheduler.class)).isEmpty();
	}

}
