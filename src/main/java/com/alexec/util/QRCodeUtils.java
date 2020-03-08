package com.alexec.util;

import cn.hutool.extra.qrcode.BufferedImageLuminanceSource;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Hashtable;

public class QRCodeUtils {
    private static final Logger logger = LoggerFactory.getLogger(QRCodeUtils.class);

    private static String getQr(String text) {
        String s = "生成二维码失败";
        int width = 1;
        int height = 1;
        // 用于设置QR二维码参数
        Hashtable<EncodeHintType, Object> qrParam = new Hashtable<EncodeHintType, Object>();
        // 设置QR二维码的纠错级别——这里选择最低L级别
        qrParam.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        qrParam.put(EncodeHintType.CHARACTER_SET, "utf-8");
        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height, qrParam);
            s = toAscii(bitMatrix);
        } catch (WriterException e) {
            e.printStackTrace();
        }
        return s;
    }

    private static String toAscii(BitMatrix bitMatrix) {
        StringBuilder sb = new StringBuilder();
        for (int rows = 0; rows < bitMatrix.getHeight(); rows++) {
            for (int cols = 0; cols < bitMatrix.getWidth(); cols++) {
                boolean x = bitMatrix.get(rows, cols);
                if (!x) {
                    // white
                    sb.append("\033[47m  \033[0m");
                } else {
                    sb.append("\033[30m  \033[0;39m");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public static void toTerminal() {
        logger.info(getQr(scanCode()));
       // System.out.println(getQr(scanCode()));
    }

    private static String scanCode() {
        MultiFormatReader formatReader = new MultiFormatReader();
        Result result = null;
        try {
            BufferedImage image = Http.
                    getResponse("https://qr.m.jd.com/show?appid=133&size=147&t="
                            + System.currentTimeMillis()).getBufferedImage();
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            HashMap hints = new HashMap();
            hints.put(EncodeHintType.CHARACTER_SET, "utf-8");//编码
            result = formatReader.decode(binaryBitmap, hints);
        } catch (NotFoundException e) {
            e.printStackTrace();
        }
        return result.getText();
    }
}