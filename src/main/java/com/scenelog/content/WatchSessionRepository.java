package com.scenelog.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WatchSessionRepository extends JpaRepository<WatchSession, Long> {

    List<WatchSession> findByUser_UserIdOrderByStartedAtDesc(Long userId);
}
