package com.alexec.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.qrcode.BufferedImageLuminanceSource;
import com.alexec.model.Constant;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class QRCodeUtils {

    private static String getQr(String text) {
        String s = "生成二维码失败";
        int width = 1;
        int height = 1;
        // 用于设置QR二维码参数
        Map<EncodeHintType, Object> qrParam = new HashMap<EncodeHintType, Object>();
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
        log.info(getQr(scanCode()));
    }

    private static String scanCode() {
        MultiFormatReader formatReader = new MultiFormatReader();
        Result result = null;
        try {
            BufferedImage image = Http.getResponse(StrUtil.format(Constant.QR_URL,
                    System.currentTimeMillis())).getBufferedImage();
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