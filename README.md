# 京东库存扫描
## 说明
配置在 application.properties 中

使用需要安装 firefox geckodriver https://github.com/mozilla/geckodriver/releases

风控会使用 WegGL 来判断是否是机器下单，selenium firefox 不支持，风险请自行承担。

购物车中存在的待抢购商品会影响下单，使用前请先删除购物车中待抢购的商品后在使用。

二维码不好扫描的话可以使用微信扫

感谢，这下面这些项目的帮助

https://github.com/Rlacat/jd-automask

https://github.com/cycz/jdBuyMask

https://github.com/shaodahong/jd-happy
