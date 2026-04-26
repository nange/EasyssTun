# EasyssTun

A simple and lightweight VPN over [easyss](https://github.com/nange/easyss) proxy for Android. It is based on a high-performance and low-overhead [tun2socks](https://github.com/heiher/hev-socks5-tunnel).


## How to Build

```bash
git clone --recursive git@github.com:nange/EasyssTun.git
cd EasyssTun
gradle assembleDebug
```

## Update Submodule 

```bash
git submodule update --init --recursive

cd path/to/submodule
git fetch origin 
git checkout <commit version> | <main>
git submodule update | git pull --recurse-submodules

---------------
git add path/to/submodule
git commit -m "Updated submodule to <version>"

git push origin <path>
```
