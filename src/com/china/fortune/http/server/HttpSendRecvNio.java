package com.china.fortune.http.server;

import com.china.fortune.socket.SocketChannelHelper;
import com.china.fortune.socket.bk.NioRWAttach;
import com.china.fortune.socket.selectorManager.NioSocketActionType;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class HttpSendRecvNio extends NioRWAttach {
    abstract public void onRecv(SelectionKey from, HttpServerRequest hRequest);
    public void addWrite(SocketChannel to, SelectionKey from, HttpServerRequest hRequest) {
        if (SocketChannelHelper.write(to, hRequest.bbData) >= 0) {
            SendData ps = new SendData(to, from, hRequest);
            if (ps.hRequest.bbData.remaining() > 0) {
                registerWrite(ps.toSc, ps);
            } else {
                registerRead(ps.toSc, ps);
            }
        }
    }

    protected boolean isInvalidSocket(long lLimited, Object objForClient) {
        return false;
    }

    private ConcurrentLinkedQueue<SendData> qAddRead = new ConcurrentLinkedQueue<SendData>();
    private class SendData {
        SocketChannel toSc;
        SelectionKey toFrom;
        HttpServerRequest hRequest;

        public SendData(SocketChannel to, SelectionKey from, HttpServerRequest hsr) {
            toSc = to;
            toFrom = from;
            hRequest = hsr;
        }
    }

    protected int iMaxHttpHeadLength = 2 * 1024;
    protected int iMaxHttpBodyLength = 2 * 1024 * 1024;

    @Override
    protected NioSocketActionType onRead(SelectionKey key, Object objForThread) {
        Object objForClient = key.attachment();
        if (objForClient != null) {
            SendData sendData = (SendData)objForClient;
            HttpServerRequest hRequest = sendData.hRequest;
            SocketChannel sc = (SocketChannel) key.channel();
            NioSocketActionType op = hRequest.readHttpHead(sc, iMaxHttpHeadLength, iMaxHttpBodyLength);
            if (op == NioSocketActionType.NSA_READ_COMPLETED) {
                if (hRequest.parseRequestAndHeader()) {
                    onRecv(sendData.toFrom, hRequest);
                    return NioSocketActionType.NSA_CLOSE;
                }
            } else {
                return op;
            }
        }
        return NioSocketActionType.NSA_CLOSE;
    }

    @Override
    protected NioSocketActionType onWrite(SelectionKey key, Object objForThread) {
        Object objForClient = key.attachment();
        if (objForClient != null) {
            HttpServerRequest hs = (HttpServerRequest) objForClient;
            SocketChannel sc = (SocketChannel) key.channel();
            if (SocketChannelHelper.write(sc, hs.bbData) > 0) {
                if (hs.bbData.remaining() == 0) {
                    hs.reset();
                    return NioSocketActionType.NSA_READ;
                } else {
                    return NioSocketActionType.NSA_WRITE;
                }
            }
        }
        return NioSocketActionType.NSA_CLOSE;
    }

    @Override
    protected void onClose(SelectionKey key) {
    }

    @Override
    protected Object createObjectInThread() {
        return null;
    }

    @Override
    protected void destroyObjectInThread(Object objForThread) {

    }
}
