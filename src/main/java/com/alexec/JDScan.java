package com.alexec;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ClassPathResource;
import cn.hutool.core.net.URLEncoder;
import cn.hutool.core.text.UnicodeUtil;
import cn.hutool.core.thread.NamedThreadFactory;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alexec.model.*;
import com.alexec.util.Http;
import com.alexec.util.QRCodeUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.cookie.Cookie;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

@Slf4j
public class JDScan {
    BlockingQueue<Goods> blockingQueue = new LinkedBlockingDeque(10);

    // region 初始化

    /**
     * 初始化数据，从缓存中读登录信息，读取配置
     */
    public void init() {
        String path = this.getClass().getResource("/").getPath();
        String configPath = path + "/config";
        if (FileUtil.exist(configPath)) {
            String data = FileUtil.readUtf8String(configPath);
            Storage.config = ObjectUtil.deserialize(Base64.decode(data));
        }
        ClassPathResource resource = new ClassPathResource("application.properties");
        Properties properties = new Properties();
        try {
            properties.load(resource.getStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
        String area = properties.get("area").toString();
        String password = properties.get("password").toString();
        String sckey = properties.get("sckey").toString();
        String skuids = properties.get("skuids").toString();
        String userAgent = properties.get("user-agent").toString();
        Long cookieExpiry = Long.valueOf(properties.get("cookie_expiry").toString());
        Long interval = Long.valueOf(properties.get("interval").toString());
        Integer threadMaxNums = Integer.valueOf(properties.get("thread_max_nums").toString());
        Long checkInterval = Long.valueOf(properties.get("check_interval").toString());

        if (StrUtil.isBlank(area)) {
            log.error("area 配置格式不正确");
            System.exit(0);
        }
        if (StrUtil.isBlank(password)) {
            log.error("密码配置格式不正确");
            System.exit(0);
        }
        if (StrUtil.isBlank(skuids)) {
            log.error("商品配置格式不正确");
            System.exit(0);
        }
        Storage.config.setArea(area);
        Storage.config.setPassword(password);
        Storage.config.setSkuids(skuids);
        Storage.config.setSckey(sckey);
        Storage.config.setCookieExpiry(cookieExpiry);
        Storage.config.setInterval(interval);
        Storage.config.setThreadMaxNums(threadMaxNums);
        Storage.config.setCheckInterval(checkInterval);
        Storage.config.setUserAgent(userAgent);
        if (needReLogin(Storage.config)) {
            Storage.config.setTicket("");
            Storage.config.setIsLogin(false);
            Storage.config.setOrderParam(new OrderParam());
        }
    }

    /**
     * 判断是否过期
     *
     * @param config
     * @return
     */
    private boolean needReLogin(Config config) {
        return checkLogin() || null == config.getCookieTime() ||
                DateUtil.parseDateTime(config.getCookieTime()).
                        isBefore(new Date(System.currentTimeMillis()
                                - config.getCookieExpiry() * 60 * 1000));
    }
    // endregion

    // region 下单相关

    /**
     * 加购物车，购买
     */
    public void buy() {
        ScheduledExecutorService sc = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("buy-single-pool", false));

        sc.execute(() -> {
            for (; ; ) {
                try {
                    // 重试三次
                    Goods goods = blockingQueue.take();
                    for (int i = 0; i < 3; i++) {
                        synchronized (this) {
                            for (int j = 0; j < goods.getNum(); j++) {
                                addCart(goods);
                            }
                            submit(goods);
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 购买，提交订单
     *
     * @param goods
     */
    private void submit(Goods goods) {
        Map<String, String> map = new HashMap<>();
        map.put("overseaPurchaseCookies", "");
        map.put("submitOrderParam.payPassword", converPassword(Storage.config.getPassword()));
        map.put("vendorRemarks", "[]");
        map.put("submitOrderParam.sopNotPutInvoice", "false");
        map.put("submitOrderParam.trackID", "TestTrackId");
        map.put("submitOrderParam.ignorePriceChange", "0");
        map.put("submitOrderParam.btSupport", "0");
        map.put("submitOrderParam.eid", Storage.config.getOrderParam().getEid());
        map.put("submitOrderParam.fp", Storage.config.getOrderParam().getFp());
        map.put("riskControl", Storage.config.getOrderParam().getRiskControl());
        map.put("submitOrderParam.isBestCoupon", "1");
        map.put("submitOrderParam.jxj", "1");
        map.put("submitOrderParam.trackId", Storage.config.getOrderParam().getTrackId());

        Map<String, String> header = new HashMap<>();
        header.put("Host", "trade.jd.com");
        header.put("Referer", "http://trade.jd.com/shopping/order/getOrderInfo.action");

        Response response = Http.getResponse(Constant.SUBMIT_URL, map, header);
        log.info(response.toString());
        if (Constant.SUCCESS_PATTERN.matcher(response.getBody()).find()) {
            sendMessage(Message.builder()
                    .text(goods.getName() + "已经下单成功")
                    .desp("请去支付" + DateUtil.formatDateTime(new Date()))
                    .build());
        }
    }

    /**
     * 加入购物车
     *
     * @param goods
     * @return
     */
    private boolean addCart(Goods goods) {
        Response response = Http.getResponse(String.format(Constant.CART_URL, goods.getSku()));
        return false;
    }

    /**
     * 密码转化
     *
     * @param password
     * @return
     */
    private String converPassword(String password) {
        StringBuilder stringBuilder = new StringBuilder();
        for (char c : password.toCharArray()) {
            stringBuilder.append("u3");
            stringBuilder.append(c);
        }
        return stringBuilder.toString();
    }
    //endregion

    // region 用户相关

    /**
     * 登录
     */
    public void login() {
        if (Storage.config.getIsLogin()) {
            return;
        }
        loadQr();
        checkQr();
        qrTicketValidation();
    }

    /**
     * 输出二维码到控制台
     */
    private void loadQr() {
        QRCodeUtils.toTerminal();
    }

    /**
     * 检测二维码扫码
     */
    private void checkQr() {
        Map<String, String> map = new HashMap<>();
        map.put("Host", "qr.m.jd.com");
        map.put("Referer", "https://passport.jd.com/new/login.aspx");
        Cookie cookie = Storage.getCookie("wlfstk_smdl");
        long start = System.currentTimeMillis();
        for (; ; ) {
            if (System.currentTimeMillis() - start > 60000) {
                log.info("扫码超时，请重新开始");
                System.exit(0);
            }
            Response response = Http.getResponse(Constant.QR_CHECK_URL
                    .concat("?callback=" + System.currentTimeMillis())
                    .concat("&appid=133")
                    .concat("&token=" + cookie.getValue())
                    .concat("&_=" + System.currentTimeMillis()), null, map);
            JSONObject res = parseJSONPtoMap(response.getBody());
            if (res != null && res.get("code").equals(200)) {
                Storage.config.setTicket(res.get("ticket").toString());
                break;
            }
            log.info("请扫码...");
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 验证二维码
     */
    private void qrTicketValidation() {
        Map<String, String> map = new HashMap<>();
        map.put("Host", "passport.jd.com");
        map.put("Referer", "https://passport.jd.com/uc/login?ltype=logout");
        Response response = Http.getResponse(Constant.QR_TICKET_VALIDATION_URL
                .concat("?t=" + Storage.config.getTicket()), null, map);
        Storage.config.setIsLogin(true);
        Storage.config.setCookieTime(DateUtil.formatDateTime(new Date()));
        saveData();
    }

    /**
     * 检查登录
     */
    private void checkLoginAndExit() {
        if (!checkLogin()) {
            sendMessage(Message.builder()
                    .text("登录已经过期了，程序自动退出")
                    .desp("自动退出")
                    .build());
            Storage.config.setIsLogin(false);
            saveData();
            System.exit(0);
        }
    }

    /**
     * 检查登录
     */
    private boolean checkLogin() {
        Map<String, String> header = new HashMap<>();
        header.put("Referer", "https://www.jd.com/");
        Response response = Http.getResponse(Constant.CHECK_LOGION_URL
                .concat("&_=" + System.currentTimeMillis()), null, header);

        if (Constant.LOGIN_PATTERN.matcher(response.getBody()).find()) {
            return true;
        }
        return false;
    }

    /**
     * 获取预下单信息，使用 Firefox chrome 会有问题
     */
    private void getPreSumbit() {
        FirefoxOptions options = new FirefoxOptions();
        options.setHeadless(true);
        WebDriver driver = new FirefoxDriver(options);
        driver.get(Constant.LOGION_URL);
        for (Cookie cookie : Storage.config.getBasicCookieStore().getCookies()) {
            if (Constant.LOGION_URL.startsWith(cookie.getDomain(), "https://".length()) ||
                    Constant.LOGION_URL.startsWith(cookie.getDomain(), "https://passport.".length())) {
                org.openqa.selenium.Cookie cookie1 = new org.openqa.selenium.Cookie(
                        //(String name, String value, String domain, String path, Date expiry, boolean isSecure, boolean isHttpOnly)
                        cookie.getName(), cookie.getValue(), cookie.getDomain(),
                        cookie.getPath(), cookie.getExpiryDate(), cookie.isSecure());
                driver.manage().addCookie(cookie1);
            }
        }
        driver.get(Constant.ORDER_URL);
        JavascriptExecutor driver_js = ((JavascriptExecutor) driver);
        String trackId = (String) ((JavascriptExecutor) driver).executeScript("return getTakId()");
        String riskControl = driver.findElement(By.id("riskControl")).getAttribute("value");
        String eid = driver.findElement(By.id("eid")).getAttribute("value");
        String fp = driver.findElement(By.id("fp")).getAttribute("value");
        driver.quit();
        Storage.config.getOrderParam().setEid(eid);
        Storage.config.getOrderParam().setFp(fp);
        Storage.config.getOrderParam().setRiskControl(riskControl);
        Storage.config.getOrderParam().setTrackId(trackId);
        log.info("预下单获取成功");
    }

    /**
     * 刷新信息
     */
    public void refreshAndSaveData() {
        ScheduledExecutorService se = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("refresh-single-pool", false));
        se.scheduleWithFixedDelay(() -> {
            // 检查登录
            checkLoginAndExit();
            // 检查是上下架
            initGoodsData();
            // 获取下单信息
            getPreSumbit();
            // 保存登录信息到文件
            saveData();
        }, 5, Storage.config.getCheckInterval(), TimeUnit.MINUTES);
    }

    /**
     * 保存config 到缓存
     */
    private void saveData() {
        String path = this.getClass().getResource("/").getPath() + "/config";
        String config = Base64.encode(ObjectUtil.serialize(Storage.config));
        FileUtil.writeUtf8String(config, path);
    }

    /**
     * jsonp 转 JSON
     *
     * @param jsonp
     * @return
     */
    private static JSONObject parseJSONPtoMap(String jsonp) {
        int startIndex = jsonp.indexOf("(");
        int endIndex = jsonp.lastIndexOf(")");
        String json = jsonp.substring(startIndex + 1, endIndex);
        return JSONUtil.parseObj(json);
    }
    //endregion

    //region 库存相关

    /**
     * 刷新库存
     */
    public void checkStockState() {
        ExecutorService e = new ThreadPoolExecutor(Storage.config.getThreadMaxNums(),
                Storage.config.getThreadMaxNums() + 10,
                5, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(100),
                new NamedThreadFactory("check-stock-executor", false));
        ScheduledExecutorService se = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("scheduled-check-single-pool", false));
        AtomicInteger count = new AtomicInteger(0);

        se.scheduleAtFixedRate(() -> {
            // >100个 sku 后不返回
            double limit = 90d;
            int goodsCount = Storage.goodsMap.size();
            int batchs = (int) Math.ceil(Math.ceil(goodsCount / limit));

            List<String> array = new ArrayList(Storage.goodsMap.keySet());
            List<List<String>> skuArray = averageAssign(array, batchs);
            Long start = System.currentTimeMillis();
            CountDownLatch latch = new CountDownLatch(batchs);
            for (List<String> strings : skuArray) {
                StringBuilder stringBuilder = new StringBuilder();
                for (String string : strings) {
                    stringBuilder.append(string);
                    stringBuilder.append(",");
                }

                e.execute(() -> {
                    try {
                        //https://c0.3.cn/stocks?type=getstocks&area=6_303_36780&d=jQuery155331123123&_=123123123&skuIds=100006784140,11609510701&buyNum=1
                        String url = Constant.STOCK_URL.concat("?type=getstocks&")
                                .concat("&area=" + Storage.config.getArea())
                                .concat("&callback=jQuery" + System.currentTimeMillis())
                                .concat("&_=" + System.currentTimeMillis())
                                .concat("&skuIds=" + stringBuilder.substring(0, stringBuilder.length() - 1));

                        Response response = Http.getResponse(url);
                        if (null == response) {
                            log.info("连接中断");
                            return;
                        }
                        JSONObject json = parseJSONPtoMap(response.getBody());
                        for (String sku : json.keySet()) {
                            if (Constant.STOCK_STATE_PATTERN.matcher(json.get(sku).toString()).find()) {
                                continue;
                            }
                            Goods goods = Storage.goodsMap.get(sku);
                            if (blockingQueue.contains(goods)) {
                                continue;
                            }
                            log.info(goods.getSku() + "有货，开始下单购买");
                            if (!blockingQueue.offer(goods)) {
                                log.info("购买队列满放弃购买");
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
//                            if (!Constant.STOCK_STATE_PATTERN.matcher(json.get(sku).toString()).find()) {
//                                Goods goods = Storage.goodsMap.get(sku);
//                                if (!blockingQueue.contains(goods)) {
//                                    log.info(goods.getSku() + "有货，开始下单购买");
//                                    if (!blockingQueue.offer(goods)) {
//                                        log.info("购买队列满放弃购买");
//                                    }
//                                }
//                            }


                });
            }
            try {
                latch.await(3, TimeUnit.SECONDS);
                log.info("第" + count.incrementAndGet() + "次查询完成，用时:" + (System.currentTimeMillis() - start) + "ms");
            } catch (
                    InterruptedException ex) {
                ex.printStackTrace();
            }
        }, 100, Storage.config.getInterval(), TimeUnit.MILLISECONDS);
    }

    /**
     * 等分数组，每次查询过多会出现问题，等分后多次查询
     *
     * @param source
     * @param n
     * @param <T>
     * @return
     */
    private static <T> List<List<T>> averageAssign(List<T> source, int n) {
        List<List<T>> result = new ArrayList<List<T>>();
        int remaider = source.size() % n;  //(先计算出余数)
        int number = source.size() / n;  //然后是商
        int offset = 0;//偏移量
        for (int i = 0; i < n; i++) {
            List<T> value = null;
            if (remaider > 0) {
                value = source.subList(i * number + offset, (i + 1) * number + offset + 1);
                remaider--;
                offset++;
            } else {
                value = source.subList(i * number + offset, (i + 1) * number + offset);
            }
            result.add(value);
        }
        return result;
    }

    /**
     * 刷新库存
     */
    @Deprecated
    public void checkStockStateOld() {
        ExecutorService e = new ThreadPoolExecutor(50, 100,
                0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(300),
                new NamedThreadFactory("check-stock-executor", false));
        //ScheduledThreadPoolExecutor se = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("check-stock-scheduled"));
        ScheduledExecutorService se = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("scheduled-check-single-pool", false));
        AtomicInteger count = new AtomicInteger(0);
        se.scheduleAtFixedRate(() -> {
            CountDownLatch latch = new CountDownLatch(Storage.goodsMap.size());
            Long start = System.currentTimeMillis();
            AtomicInteger i = new AtomicInteger(0);
            for (Map.Entry<String, Goods> entry : Storage.goodsMap.entrySet()) {
                Goods goods = entry.getValue();
                if (goods == null) {
                    continue;
                }
                e.execute(() -> {
                    try {
                        String url = Constant.STOCK_URL.concat("skuId=" + goods.getSku())
                                .concat("&venderId=" + goods.getVenderId())
                                .concat("&cat=" + goods.getCat())
                                .concat("&area=" + Storage.config.getArea())
                                .concat("&buyNum=" + goods.getNum());
                        Response response = Http.getResponse(url);
                        if (!Constant.STOCK_STATE_PATTERN.matcher(response.getBody()).find()) {
                            if (!blockingQueue.contains(goods)) {
                                log.info(goods.getSku() + "有货，开始下单购买");
                                if (!blockingQueue.offer(goods)) {
                                    log.info("购买队列满放弃购买");
                                }
                            }
                        }
                        //log.info("第" + i.incrementAndGet() + "," + goods.getName());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await(3, TimeUnit.SECONDS);
                log.info("第" + count.incrementAndGet() + "次查询完成，用时:" + (System.currentTimeMillis() - start) + "ms");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }

        }, 100, 10000, TimeUnit.MICROSECONDS);
    }

    public void initGoodsData() {
        String goodsListString = Storage.config.getSkuids();
        String[] goodsList = goodsListString.split(",");
        ExecutorService e = new ThreadPoolExecutor(10, 20,
                0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(500),
                new NamedThreadFactory("init-goods-executor", false));
        CountDownLatch latch = new CountDownLatch(goodsList.length);
        AtomicInteger count = new AtomicInteger(0);
        for (String item : goodsList) {
            e.execute(() -> {
                try {
                    String[] goodsAndNum = item.split(":");
                    Goods goods = new Goods();
                    goods.setSku(goodsAndNum[0]);
                    if (goodsAndNum.length > 1) {
                        goods.setNum(Integer.parseInt(goodsAndNum[1]));
                    } else {
                        goods.setNum(1);
                    }
                    Response response = Http.getResponse(String.format(Constant.GOODS_URL, goods.getSku()));
                    String body = response.getBody();
                    Matcher goodsNameMatcher = Constant.GOODS_NAME_PATTERN.matcher(body);
                    if (goodsNameMatcher.find()) {
                        goods.setName(UnicodeUtil.toString(goodsNameMatcher.group(1)));
                    }

                    Matcher takeOffPattern = Constant.TAKEOFF_PATTERN.matcher(body);
                    if (takeOffPattern.find()) {
                        try {
                            Storage.goodsMap.remove(goods.getSku());
                        } finally {
                            log.info(goods.getName() + "已经下架，总下架数" + count.incrementAndGet());
                            latch.countDown();
                            return;
                        }
                    }
                    Matcher venderIdMatcher = Constant.VENDERID_PATTERN.matcher(body);
                    if (venderIdMatcher.find()) {
                        goods.setVenderId(venderIdMatcher.group(1));
                    }
                    Matcher catMatcher = Constant.CAT_PATTERN.matcher(body);
                    if (catMatcher.find()) {
                        goods.setCat(catMatcher.group(1));
                    }
                    Storage.goodsMap.put(goods.getSku(), goods);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
            log.info("商品数据检测完成");
            e.shutdown();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }
//endregion

//region 发送消息

    /**
     * 发送消息
     *
     * @param message
     */
    private void sendMessage(Message message) {
        if (StrUtil.isNotBlank(Storage.config.getSckey())) {
            Map<String, String> map = new HashMap<>();
            map.put("text", new URLEncoder().encode(message.getText(), Charset.forName("utf-8")));
            map.put("desp", new URLEncoder().encode(message.getDesp(), Charset.forName("utf-8")));
            Http.getResponse(String.format(Constant.FTQQ_URL, Storage.config.getSckey()), map, null);
            log.info("发送下单消息");
        }
    }
    //endregion

}
