# EasyssTun

> **Note:** 此仓库 fork 自 [bingooo/EasyssTun](https://github.com/bingooo/EasyssTun)。

基于 [easyss](https://github.com/nange/easyss) 代理的轻量级 Android VPN，底层使用高性能、低开销的 [tun2socks](https://github.com/heiher/hev-socks5-tunnel) 实现。

## APP截图

<div align="center">
  <img src="assets/app1.jpg" width="30%" alt="App Screenshot 1" />
  &nbsp;&nbsp;
  <img src="assets/app1_1.jpg" width="30%" alt="App Screenshot 2" />
  &nbsp;&nbsp;
  <img src="assets/app2.jpg" width="30%" alt="App Screenshot 3" />
</div>
<div align="center">
  <img src="assets/app3.jpg" width="30%" alt="App Screenshot 4" />
  &nbsp;&nbsp;
  <img src="assets/app4.jpg" width="30%" alt="App Screenshot 5" />
  &nbsp;&nbsp;
  <img src="assets/app5.jpg" width="30%" alt="App Screenshot 6" />
</div>

## 构建方式

```bash
git clone https://github.com/nange/EasyssTun.git
cd EasyssTun
make build
```

首次构建时会自动从 GitHub Release 下载 `libeasyss.aar` 与 `hev-socks5-tunnel.aar` 到 `app/libs/`，无需 NDK。

也可直接在Release页面下载编译好的APK文件。

## 升级 tun2socks

hev-socks5-tunnel（tun2socks）以预编译 AAR 形式引入，版本由 `version.properties` 中的 `hevSocks5TunnelVersion` 锁定。升级步骤：

1. 到 [nange/hev-socks5-tunnel](https://github.com/nange/hev-socks5-tunnel)（fork 的 `easyss` 分支）合入上游改动并发布新版本（打 tag 触发 CI 构建，release 资产中包含 `hev-socks5-tunnel.aar`）。
2. 更新本仓库 `version.properties` 中的 `hevSocks5TunnelVersion` 为新 tag。
3. 删除本地 `app/libs/hev-socks5-tunnel.aar`，下次构建自动重新下载。
