package com.heiher.hev.socks5.tunnel

object HevSocks5Tunnel {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    external fun hev_socks5_tunnel_main(configPath: String, fd: Int)

    @JvmStatic
    external fun hev_socks5_tunnel_stop()
}
