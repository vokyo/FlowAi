package com.vokyo.backend.issue.dto;

import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueStatus;
import jakarta.validation.constraints.Size;

public record UpdateIssueRequest

        (@Size(max = 240)
         String title,

         @Size(max = 10000)
         String description,

         IssueStatus  status,

         IssuePriority priority
         ) {

}
