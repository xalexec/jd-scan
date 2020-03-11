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
    // pid = sku
    private String sku;
    private String name;
    private Integer num;
    private String cat;
    private String venderId;
    private String cartVenderId;
    private String ptype;
    private String targetId;
    private String promoID;
    private Boolean inCart;
    private Integer cartNum;
}
