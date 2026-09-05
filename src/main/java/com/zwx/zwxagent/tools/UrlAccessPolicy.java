package com.zwx.zwxagent.tools;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Component
public class UrlAccessPolicy {

    public URI validateHttpUrl(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid URL");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Only credential-free HTTP(S) URLs are allowed");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                requirePublic(address);
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Unable to resolve host: " + uri.getHost());
        }
        return uri;
    }

    private void requirePublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            throw new IllegalArgumentException("Access to private network addresses is not allowed");
        }
        if (address instanceof Inet6Address && ((Inet6Address) address).getAddress()[0] != 0
                && (((Inet6Address) address).getAddress()[0] & 0xfe) == 0xfc) {
            throw new IllegalArgumentException("Access to private network addresses is not allowed");
        }
        if (address instanceof Inet4Address) {
            byte[] octets = ((Inet4Address) address).getAddress();
            if ((octets[0] & 0xff) == 100 && (octets[1] & 0xf0) == 64) {
                throw new IllegalArgumentException("Access to private network addresses is not allowed");
            }
            if ((octets[0] & 0xff) == 198 && (octets[1] & 0xff) == 18) {
                throw new IllegalArgumentException("Access to private network addresses is not allowed");
            }
        }
    }
}
