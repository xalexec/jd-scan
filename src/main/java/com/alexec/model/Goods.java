package com.alexec.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Goods {
    private String sku;
    private Integer num;
    private String cat;
    private String venderId;
    private String name;
}
