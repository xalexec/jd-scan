package com.alexec;

public class Main {

    public static void main(String[] args) {
        JDScan jd = new JDScan();
        // 初始化数据，从缓存中读登录信息，读取配置
        jd.init();
        // 扫码登录
        jd.login();
        // 扫描上下架和商品信息 检查登录 获取预下单信息 保存登录信息到文件
        jd.refreshAndSaveData();
        // 检查库存
        jd.checkStockState();
        // 下单
        jd.buy();
        // TODO
        // 二维码在某些字体上是长方形的，而且大小不可调。现在用微信的话会好扫一点
        // 风控会使用 webGL，暂时将 Headless 设置为 false，但是会跳出浏览器
        // 购物车只能放70个商品，在售商品超过70个会出现不能加购情况，需要限制
        // 商品大量有货会造成大量尝试下单，占满购买队列
    }
}
