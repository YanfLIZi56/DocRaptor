package com.yanglizi.docraptor.dto.request;

import lombok.Data;

/** PUT /api/documents/{id}/enabled 请求体（契约 4.8）。 */
@Data
public class DocumentEnabledRequest {
    private Boolean enabled;
}
