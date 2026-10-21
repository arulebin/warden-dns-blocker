# ndk-build entry for :app. Builds libhev-socks5-tunnel.so (a userspace TCP/IP stack
# that turns tunnel packets into SOCKS5 connections) from the pinned submodule.
# Its JNI class is set with -DPKGNAME/-DCLSNAME in app/build.gradle.kts.
include $(call my-dir)/hev-socks5-tunnel/Android.mk
