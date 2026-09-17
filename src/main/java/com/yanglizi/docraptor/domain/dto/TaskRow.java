package com.yanglizi.docraptor.domain.dto;

import com.yanglizi.docraptor.domain.entity.AsyncTask;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 异步任务行 + 关联文档名（契约 7.1 的 documentName）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskRow extends AsyncTask {
    private String documentName;
}
