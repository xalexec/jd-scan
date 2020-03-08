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
        // 扫描上下架和商品信息
        jd.initGoodsData();
        // 检查库存
        jd.checkStockState();
        // 下单
        jd.buy();
        // TODO
        // 二维码在某些字体上是方形的，而且大小不可调，现在用微信扫好扫一点
        // 购物改数量
        // 风控会使用 webGL
        // checklogin
    }
}
