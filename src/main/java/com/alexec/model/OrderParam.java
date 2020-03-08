package com.alexec.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderParam implements Serializable {
    private String eid;
    private String trackId;
    private String fp;
    private String riskControl;
}
