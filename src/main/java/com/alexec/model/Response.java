package com.alexec.model;

import lombok.Builder;
import lombok.Data;
import org.apache.http.Header;

import java.awt.image.BufferedImage;

@Data
@Builder
public class Response {
    private String body;
    private String cookie;
    private Header[] header;
    private BufferedImage bufferedImage;
    private Integer statusCode;
}
