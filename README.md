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
git clone --recursive https://github.com/nange/EasyssTun.git
cd EasyssTun
make build
```

也可直接在Release页面下载编译好的APK文件。

## 更新子模块 

```bash
git submodule update --init --recursive

cd path/to/submodule
git fetch origin 
git checkout <commit version> | <main>
git submodule update | git pull --recurse-submodules

---------------
git add path/to/submodule
git commit -m "更新子模块至 <version>"

git push origin <path>
```
