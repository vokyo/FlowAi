package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueWatcherRepository extends JpaRepository<IssueWatcher, IssueWatcherId> {

}
