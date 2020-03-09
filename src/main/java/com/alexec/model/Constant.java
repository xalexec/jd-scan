package com.alexec.model;

import java.util.regex.Pattern;

public final class Constant {
    private Constant() {
    }

    public final static String LOGION_URL = "https://passport.jd.com/new/login.aspx";
    public final static String CHECK_LOGION_URL = "https://passport.jd.com/loginservice.aspx?method=Login&callback=jsonpLogin&_={}";

    public final static String QR_URL = "https://qr.m.jd.com/show?appid=133&size=147&t=";
    public final static String QR_CHECK_URL = "https://qr.m.jd.com/check?callback={}&appid=133&token={}&_={}";
    public final static String QR_TICKET_VALIDATION_URL = "https://passport.jd.com/uc/qrCodeTicketValidation?t={}";
    public final static String GOODS_URL = "https://item.jd.com/{}.html";

    public final static String STOCK_URL = "http://c0.3.cn/stock?skuId={}&venderId={}&cat={}&area={}&buyNum={}";
    public final static String STOCKS_URL = "http://c0.3.cn/stocks?type=getstocks&area={}&callback=jQuery{}&_={}&skuIds={}";

    public final static String ORDER_URL = "https://trade.jd.com/shopping/order/getOrderInfo.action";
    public final static String SUBMIT_URL = "https://trade.jd.com/shopping/order/submitOrder.action";
    public final static String CART_URL = "https://cart.jd.com/gate.action?pid={}&pcount=1&ptype=1";
    public final static String CHANGE_CART_URL = "https://cart.jd.com/changeNum.action";

    public final static String FTQQ_URL = "https://sc.ftqq.com/{}.send";

    public final static Pattern STOCK_STATE_PATTERN = Pattern.compile("无货");
    public final static Pattern SKU_PATTERN = Pattern.compile("\\d+");
    public final static Pattern VENDERID_PATTERN = Pattern.compile("venderId:(\\d+),");
    public final static Pattern CAT_PATTERN = Pattern.compile("cat: \\[(\\d{3,5},\\d{3,5},\\d{3,5})\\]");
    public final static Pattern TAKEOFF_PATTERN = Pattern.compile("该商品已下柜，欢迎挑选其他商品！");
    public final static Pattern SUCCESS_PATTERN = Pattern.compile("\"success\": ?true");
    public final static Pattern GOODS_NAME_PATTERN = Pattern.compile("name: '((:?\\\\u(:?[a-z]|[0-9]){4})+)'");
    public final static Pattern LOGIN_PATTERN = Pattern.compile("\"IsAuthenticated\":true");

}
