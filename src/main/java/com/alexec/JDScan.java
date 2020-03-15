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
import org.apache.http.HttpStatus;
import org.apache.http.cookie.Cookie;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

@Slf4j
public class JDScan {
    final BlockingQueue<Goods> buyBlockingQueue = new ArrayBlockingQueue(10);
    final Semaphore addCartSemaphore = new Semaphore(0);
    final Object lock = new Object();
    // region 初始化

    /**
     * 初始化数据，从缓存中读登录信息，读取配置
     */
    public void init() {
        if (FileUtil.exist(Constant.CONFIG_PATH)) {
            String data = FileUtil.readUtf8String(Constant.CONFIG_PATH);
            try {
                Storage.config = ObjectUtil.deserialize(Base64.decode(data));
            } catch (Exception e) {
                Storage.config = new Config();
            }
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
        Boolean headless = Boolean.valueOf(properties.get("headless").toString());

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
        Storage.config.setHeadless(headless);

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
        return !checkLogin() || (null == config.getCookieTime() ||
                DateUtil.parseDateTime(config.getCookieTime()).
                        isBefore(new Date(System.currentTimeMillis()
                                - config.getCookieExpiry() * 60 * 1000)));
    }
    // endregion

    // region 下单相关

    /**
     * 加购物车，购买
     */
    public void buy() {
        ExecutorService buyExecutor = Executors.newSingleThreadExecutor(
                new NamedThreadFactory("buy-single-pool", false));

        buyExecutor.execute(() -> {
            for (; ; ) {
                try {
                    // 重试三次
                    Goods goods = buyBlockingQueue.take();
                    for (int i = 0; i < 3; i++) {
                        synchronized (this) {
                            log.info("开始第{}次尝试购买，sku:{}「{}」，", i + 1, goods.getSku(), goods.getName());
                            selectGoods(goods);
                            if (!submit(goods)) {
                                cancelAllGoods();
                                TimeUnit.MILLISECONDS.sleep(700);
                            }
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
    private boolean submit(Goods goods) {
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
        if (Constant.SUCCESS_PATTERN.matcher(response.getBody()).find()) {
            sendMessage(Message.builder()
                    .text(goods.getName() + "已经下单成功")
                    .desp("请去支付" + DateUtil.formatDateTime(new Date()))
                    .build());
            return true;
        } else {
            log.info("下单失败，失败原因请查看输出，{}「{}」", goods.getSku(), goods.getName());
            log.info(response.toString());
        }
        return false;
    }

    /**
     * 修改购物车中的数量
     *
     * @param goods
     * @return
     */
    private synchronized boolean changeCartNum(Goods goods) {
        for (int i = 0; i < 3; i++) {
            Map<String, String> header = new HashMap<>();
            header.put("Referer", "https://cart.jd.com/cart");

            Map<String, String> map = new HashMap<>();
            map.put("t", "0");
            map.put("venderId", goods.getCartVenderId());
            map.put("pid", goods.getSku());
            map.put("pcount", String.valueOf(goods.getNum()));
            map.put("ptype", goods.getPtype());
            map.put("targetId", goods.getTargetId());
            map.put("promoID", goods.getPromoID());
            map.put("outSkus", "");
            map.put("random", String.valueOf(System.currentTimeMillis()));
            map.put("locationId", Storage.config.getArea());

            Response response = Http.getResponse(Constant.CHANGE_NUM_CART_URL, map, header);
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (Constant.CHANGE_CART_NUM_SUCCESS_PATTERN.matcher(response.getBody()).find()) {
                return true;
            } else {
                log.info("修改数量失败，sku:{}「{}」", goods.getSku(), goods.getName());
            }
        }
        return false;
    }

    /**
     * 取消购物车所有商品勾选
     */
    private void cancelAllGoods() {
        Map<String, String> header = new HashMap<>();
        header.put("Referer", "https://cart.jd.com/cart");

        Map<String, String> map = new HashMap<>();
        map.put("t", "0");
        map.put("outSkus", "");
        map.put("random", String.valueOf(System.currentTimeMillis()));
        map.put("locationId", Storage.config.getArea());
        Http.getResponse(Constant.CANCEL_ALL_GOODS_URL, map, header);
    }

    /**
     * 选中购物车中的商品
     *
     * @param goods
     */
    private synchronized void selectGoods(Goods goods) {
        Map<String, String> header = new HashMap<>();
        header.put("Referer", "https://cart.jd.com/cart");

        Map<String, String> map = new HashMap<>();
        map.put("outSkus", "");
        map.put("pid", goods.getSku());
        map.put("ptype", goods.getPtype());
        map.put("targetId", goods.getTargetId());
        map.put("promoID", goods.getPromoID());
        map.put("venderId", goods.getCartVenderId());
        map.put("t", "0");

        Http.getResponse(Constant.SELECT_GOODS_URL, map, header);
    }

    /**
     * 获取购物车中的商品详情
     */
    private void getCartGoods() {
        Response response = Http.getResponse(StrUtil.format(Constant.CART_URL, System.currentTimeMillis()));
        Matcher matcher = Constant.CHANGE_NUM_PATTERN.matcher(response.getBody());
        while (matcher.find()) {
            String sku = matcher.group(2);
            Goods goods = Storage.goodsMap.get(sku);
            if (goods != null) {
                goods.setCartVenderId(matcher.group(1));
                goods.setCartNum(Integer.parseInt(matcher.group(3)));
                goods.setPtype(matcher.group(4));
                String promoId = matcher.group(5);
                goods.setPromoID(StrUtil.isNotBlank(promoId) ? promoId : "0");
                goods.setTargetId(StrUtil.isNotBlank(promoId) ? promoId : "0");
                goods.setInCart(true);
            }
        }
    }

    /**
     * 加购，改数量，取消勾选
     */
    private void addCartAndChangeNumAndCancelAll() {
        try {
            addCartSemaphore.acquire();
            // 取出购物车已经有信息
            getCartGoods();
            // 加入购物车
            Iterator<Map.Entry<String, Goods>> iterator = Storage.goodsMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Goods> entry = iterator.next();
                if (null == entry.getValue().getInCart() || !entry.getValue().getInCart()) {
                    if (!addCart(entry.getValue())) {
                        // 商品不能加入购物车，删除
                        iterator.remove();
                    }
                }
            }
            // 取出购物车已经有信息
            getCartGoods();
            // 修改购物车数量
            for (Map.Entry<String, Goods> entry : Storage.goodsMap.entrySet()) {
                if (!entry.getValue().getCartNum().equals(entry.getValue().getNum())) {
                    // 修改数量
                    changeCartNum(entry.getValue());
                }
            }
            // 取消全部勾选
            cancelAllGoods();
            log.info("加购物车检查完成");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 加入购物车
     *
     * @param goods
     * @return
     */
    private synchronized boolean addCart(Goods goods) {
        // 加购太快会失败，不能多线程
        for (int i = 0; i < 3; i++) {
            Response response = Http.getResponse(StrUtil.format(Constant.ADD_CART_URL,
                    System.currentTimeMillis(), goods.getSku(), System.currentTimeMillis()));
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Matcher matcher = Constant.CART_SUCCESS_PATTERN.matcher(response.getBody());
            if (matcher.find()) {
                log.info("加入购物车成功，sku:{}「{}」", goods.getSku(), goods.getName());
                return true;
            } else {
                log.info("加入购物车失败，sku:{}，「{}」", goods.getSku(), goods.getName());
                log.info(response.toString());
            }
        }
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
            Response response = Http.getResponse(StrUtil.format(Constant.QR_CHECK_URL,
                    System.currentTimeMillis(),
                    cookie.getValue(),
                    System.currentTimeMillis()), null, map);
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
        Http.getResponse(StrUtil.format(Constant.QR_TICKET_VALIDATION_URL
                , Storage.config.getTicket()), null, map);
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
            log.info("登录已经过期了，程序自动退出");
            System.exit(0);
        }
        log.info("检查登录完成");
    }

    /**
     * 检查登录
     */
    private boolean checkLogin() {
        Map<String, String> header = new HashMap<>();
        header.put("Referer", "https://www.jd.com/");
        Response response = Http.getResponse(StrUtil.format(Constant.CHECK_LOGION_URL
                , System.currentTimeMillis()), null, header);

        if (Constant.LOGIN_PATTERN.matcher(response.getBody()).find()) {
            return true;
        }
        return false;
    }

    /**
     * 获取预下单信息，使用 Firefox，chrome 会有问题
     */
    private void getPreSumbit() {
        // 购物车没有勾选，直接到下单页会报错
        for (Map.Entry<String, Goods> entry : Storage.goodsMap.entrySet()) {
            Goods goods = entry.getValue();
            selectGoods(goods);
            break;
        }
        FirefoxProfile profile = new FirefoxProfile();
        profile.setPreference("general.useragent.override", Storage.config.getUserAgent());
        FirefoxOptions options = new FirefoxOptions();
        options.setHeadless(Storage.config.getHeadless());
        options.setProfile(profile);
        WebDriver driver = new FirefoxDriver(options);
        driver.get(Constant.LOGION_URL);
        for (Cookie cookie : Storage.config.getBasicCookieStore().getCookies()) {
            if (Constant.LOGION_URL.startsWith(cookie.getDomain(), "https://".length()) ||
                    Constant.LOGION_URL.startsWith(cookie.getDomain(), "https://passport.".length())) {
                org.openqa.selenium.Cookie cookie1 = new org.openqa.selenium.Cookie(
                        cookie.getName(), cookie.getValue(), cookie.getDomain(),
                        cookie.getPath(), cookie.getExpiryDate(), cookie.isSecure());
                driver.manage().addCookie(cookie1);
            }
        }
        driver.get(Constant.ORDER_URL);
        String trackId = (String) ((JavascriptExecutor) driver).executeScript("return getTakId()");
        String riskControl = driver.findElement(By.id("riskControl")).getAttribute("value");
        String eid = driver.findElement(By.id("eid")).getAttribute("value");
        String fp = driver.findElement(By.id("fp")).getAttribute("value");
        driver.quit();
        Storage.config.getOrderParam().setEid(eid);
        Storage.config.getOrderParam().setFp(fp);
        Storage.config.getOrderParam().setRiskControl(riskControl);
        Storage.config.getOrderParam().setTrackId(trackId);
        // 取消勾选
        cancelAllGoods();
        log.info("预下单获取成功");
    }

    /**
     * 刷新信息
     */
    public void refreshAndSaveData() {
        ScheduledExecutorService refreshSe = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("refresh-single-pool", false));
        ScheduledExecutorService preSubSe = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("getpresumbit-single-pool", false));
        ExecutorService checkGoodsExecutor = new ThreadPoolExecutor(4, 8,
                0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(200),
                new NamedThreadFactory("init-goods-executor", false));
        refreshSe.scheduleWithFixedDelay(() -> {
            try {
                // 检查登录
                checkLoginAndExit();
                // 检查是上下架
                initGoodsData(checkGoodsExecutor);
                // 加入购物车并改数量并勾选
                addCartAndChangeNumAndCancelAll();
                // 保存登录信息到文件
                saveData();
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }, 0, Storage.config.getCheckInterval(), TimeUnit.MINUTES);

        preSubSe.scheduleWithFixedDelay(() -> {
            try {
                // 获取下单信息
                getPreSumbit();
                // 保存登录信息到文件
                saveData();
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }, 1, 600, TimeUnit.MINUTES);
    }

    /**
     * 保存config 到缓存
     */
    private void saveData() {
        String config = Base64.encode(ObjectUtil.serialize(Storage.config));
        FileUtil.writeUtf8String(config, Constant.CONFIG_PATH);
        log.info("数据存储完成");
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

    // region 库存相关

    /**
     * 刷新库存
     */
    public void checkStockState() {
        ExecutorService checkExecutor = new ThreadPoolExecutor(Storage.config.getThreadMaxNums(),
                Storage.config.getThreadMaxNums() + Storage.config.getThreadMaxNums() / 2,
                5, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(100),
                new NamedThreadFactory("check-stock-executor", false));
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("scheduled-check-single-pool", false));
        AtomicInteger count = new AtomicInteger(0);

        scheduledExecutor.scheduleAtFixedRate(() -> {
            // >100个 sku 后不返回
            double limit = 90d;
            int goodsCount = Storage.goodsMap.size();
            if (goodsCount < 1) {
                return;
            }
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

                checkExecutor.execute(() -> {
                    try {
                        String url = StrUtil.format(Constant.STOCKS_URL,
                                Storage.config.getArea(),
                                System.currentTimeMillis(),
                                System.currentTimeMillis(),
                                stringBuilder.toString());

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
                            synchronized (lock) {
                                if (buyBlockingQueue.contains(goods)) {
                                    continue;
                                }
                                log.info("开始下单购买，sku:{}「{}」", goods.getSku(), goods.getName());
                                if (!buyBlockingQueue.offer(goods)) {
                                    log.info("购买队列满放弃购买，sku:{}「{}」", goods.getSku(), goods.getName());
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        log.error(e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await(3, TimeUnit.SECONDS);
                log.info("第{}次查询完成，监控:{}，用时:{}ms", count.incrementAndGet(),
                        goodsCount, System.currentTimeMillis() - start);
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
                        String url = StrUtil.format(Constant.STOCK_URL, goods.getSku(), goods.getVenderId(),
                                goods.getCat(), Storage.config.getArea(), goods.getNum());
                        Response response = Http.getResponse(url);
                        if (!Constant.STOCK_STATE_PATTERN.matcher(response.getBody()).find()) {
                            if (!buyBlockingQueue.contains(goods)) {
                                log.info("开始下单购买，sku:{}「」", goods.getSku(), goods.getName());
                                if (!buyBlockingQueue.offer(goods)) {
                                    log.info("购买队列满放弃购买");
                                }
                            }
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await(3, TimeUnit.SECONDS);
                log.info("第{}次查询完成，用时:{}ms", count.incrementAndGet(), System.currentTimeMillis() - start);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }

        }, 100, 10000, TimeUnit.MICROSECONDS);
    }

    private void initGoodsData(ExecutorService executor) {
        try {
            String goodsListString = Storage.config.getSkuids();
            String[] goodsList = goodsListString.split(",");

            CountDownLatch latch = new CountDownLatch(goodsList.length);
            AtomicInteger stockCount = new AtomicInteger(0);
            AtomicInteger takeOffCount = new AtomicInteger(0);

            for (String item : goodsList) {
                executor.execute(() -> {
                    try {
                        String[] goodsAndNum = item.split(":");
                        Goods goods = new Goods();
                        goods.setSku(goodsAndNum[0]);
                        if (goodsAndNum.length > 1) {
                            goods.setNum(Integer.parseInt(goodsAndNum[1]));
                        } else {
                            goods.setNum(1);
                        }
                        Response response = Http.getResponse(StrUtil.format(Constant.GOODS_URL, goods.getSku()));
                        if (null == response || !response.getStatusCode().equals(HttpStatus.SC_OK)) {
                            Storage.goodsMap.remove(goods.getSku());
                            log.info("总{}，监控{}，放弃{}，sku:{}「{}」", goodsList.length, stockCount.get(),
                                    takeOffCount.incrementAndGet(), goods.getSku(), "获取商品超时");
                            return;
                        }
                        String body = response.getBody();
                        Matcher goodsNameMatcher = Constant.GOODS_NAME_PATTERN.matcher(body);
                        if (goodsNameMatcher.find()) {
                            goods.setName(UnicodeUtil.toString(goodsNameMatcher.group(1)));
                        }

                        Matcher takeOffPattern = Constant.TAKEOFF_PATTERN.matcher(body);
                        if (takeOffPattern.find()) {
                            Storage.goodsMap.remove(goods.getSku());
                            log.info("总{}，监控{}，放弃{}，sku:{}「{}」", goodsList.length, stockCount.get(),
                                    takeOffCount.incrementAndGet(), goods.getSku(), goods.getName());
                            return;
                        }
                        Matcher venderIdMatcher = Constant.VENDERID_PATTERN.matcher(body);
                        if (venderIdMatcher.find()) {
                            goods.setVenderId(venderIdMatcher.group(1));
                        }
                        Matcher catMatcher = Constant.CAT_PATTERN.matcher(body);
                        if (catMatcher.find()) {
                            goods.setCat(catMatcher.group(1));
                        }
                        log.info("总{}，监控{}，放弃{}，sku:{}「{}」", goodsList.length, stockCount.incrementAndGet(),
                                takeOffCount.get(), goods.getSku(), goods.getName());
                        Storage.goodsMap.put(goods.getSku(), goods);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            log.info("商品数据检测完成，总{}，监控{}，放弃{}", goodsList.length, stockCount.get(), takeOffCount.get());
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        } finally {
            addCartSemaphore.release();
        }
    }
    // endregion

    // region 发送消息

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

            Http.getResponse(StrUtil.format(Constant.FTQQ_URL, Storage.config.getSckey()), map, null);
            log.info("发送消息，{}", message.getText());
        }
    }
    // endregion

}
