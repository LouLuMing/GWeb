package com.china.fortune.proxy;

import com.china.fortune.file.FileHelper;
import com.china.fortune.global.Log;
import com.china.fortune.http.httpHead.HttpHeader;
import com.china.fortune.http.httpHead.HttpResponse;
import com.china.fortune.myant.TargetInterface;
import com.china.fortune.processflow.ProcessAction;
import com.china.fortune.proxy.host.Host;
import com.china.fortune.proxy.host.HostList;
import com.china.fortune.proxy.host.HostSocket;
import com.china.fortune.socket.SocketChannelHelper;
import com.china.fortune.socket.selectorManager.NioRWAttach;
import com.china.fortune.socket.selectorManager.NioSocketActionType;
import com.china.fortune.string.StringAction;
import com.china.fortune.struct.FastList;
import com.china.fortune.xml.XmlNode;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.china.fortune.http.httpHead.HttpHeader.csContentLength;
import static com.china.fortune.http.httpHead.HttpHeader.iMinGZipLength;

public class ProxyServer extends NioRWAttach implements TargetInterface {
    private ConcurrentLinkedQueue<HttpProxyRequest> qObjsForClient = new ConcurrentLinkedQueue<HttpProxyRequest>();
    private int iMaxHttpHeadLength = 2 * 1024;
    private int iMaxHttpBodyLength = 2 * 1024 * 1024;

    @Override
    protected SelectionKey acceptSocket(SocketChannel sc) {
        SelectionKey sk = super.acceptSocket(sc);
        if (sk != null) {
            Object obj = onAccept(sc);
            sk.attach(obj);
        }
        return sk;
    }

    @Override
    protected NioSocketActionType onRead(SelectionKey key, Object objForThread) {
        Object objForClient = key.attachment();
        if (objForClient != null) {
            HttpProxyRequest hRequest = (HttpProxyRequest)objForClient;
            SocketChannel sc = (SocketChannel) key.channel();
            NioSocketActionType op = hRequest.readHttpHead(sc, iMaxHttpHeadLength, iMaxHttpBodyLength);
            if (op == NioSocketActionType.OP_READ_COMPLETED) {
                if (hRequest.skClient == null
                        || hRequest.skClient == key) {
                    if (hRequest.parseRequest()) {
                        return doAction(key, hRequest);
                    }
                } else {
                    hRequest.readyToWrite();
                    interestOps(hRequest.skClient, SelectionKey.OP_WRITE);
                    return NioSocketActionType.OP_NULL;
                }
            } else {
                return op;
            }
        }
        return NioSocketActionType.OP_CLOSE;
    }

    @Override
    protected NioSocketActionType onWrite(SelectionKey key, Object objForThread) {
        Object objForClient = key.attachment();
        if (objForClient != null) {
            HttpProxyRequest hReq = (HttpProxyRequest) objForClient;
            return hReq.write(key);
        }
        return NioSocketActionType.OP_CLOSE;
    }

    @Override
    protected boolean isInvalidSocket(long lLimit, Object objForClient) {
        if (objForClient != null) {
            HttpProxyRequest hhb = (HttpProxyRequest) objForClient;
            return hhb.lActiveTicket < lLimit;
        } else {
            return true;
        }
    }

    protected Object onAccept(SocketChannel sc) {
        HttpProxyRequest hhb = qObjsForClient.poll();
        if (hhb == null) {
            hhb = new HttpProxyRequest();
        } else {
            hhb.clear();
        }

        InetSocketAddress isa = (InetSocketAddress) sc.socket().getRemoteSocketAddress();
        if (isa != null) {
            hhb.bRemoteAddr = isa.getAddress().getAddress();
        }
        return hhb;
    }

    @Override
    protected void onClose(SelectionKey key) {
        Object objForClient = key.attachment();
        if (objForClient != null) {
            HttpProxyRequest hReq = (HttpProxyRequest)objForClient;
            if (hReq.skClient == key) {
                if (hReq.skChannel != null) {
                    freeKeyAndSocket(hReq.skChannel);
                }
            } else if (hReq.skChannel == key) {
                if (hReq.skClient != null) {
                    freeKeyAndSocket(hReq.skClient);
                }
            }
            hReq.skClient = null;
            hReq.skChannel = null;
            hReq.pl = null;
            hReq.closeFileChannel();
            qObjsForClient.add(hReq);
        }
    }

    @Override
    protected Object createObjectInThread() {
        return null;
    }

    @Override
    protected void destroyObjectInThread(Object objForThread) {
    }

    private ProxyManager proxyManager = new ProxyManager();
    private ConcurrentLinkedQueue<HostSocket> qAddRead = new ConcurrentLinkedQueue<>();
    @Override
    protected int selectAction(FastList<SelectionKey> qSelectedKey) {

        while (!qAddRead.isEmpty()) {
            HostSocket ps = qAddRead.poll();
            if (ps != null) {
                SelectionKey skFrom = ps.from;
                if (skFrom != null) {
                    Object objForClient = skFrom.attachment();
                    if (objForClient != null) {
                        HttpProxyRequest hs = (HttpProxyRequest) objForClient;
                        SelectionKey skTo = registerWrite(ps.to);
                        if (skTo != null) {
                            hs.readyToWrite();
                            hs.skChannel = skTo;
                            hs.skClient = skFrom;
                            skTo.attach(objForClient);
                        }
                    }
                }
            }
        }
        return super.selectAction(qSelectedKey);
    }

    public void addPairSocketToRead(SelectionKey from, SocketChannel to) {
        HostSocket hs = new HostSocket();
        hs.from = from;
        hs.to = to;
        qAddRead.add(hs);
    }

    protected void addResource(String resource, String path) {
        proxyManager.add(resource, path);
    }

    public NioSocketActionType doAction(SelectionKey key, HttpProxyRequest hReq) {
        HttpResponse hRes = new HttpResponse();
        String sResource = hReq.getResource();
        HostList pl = proxyManager.getMatch(sResource);
        if (pl != null) {
            if (hReq.pl == pl) {
                hReq.readyToWrite();
                interestOps(hReq.skChannel, SelectionKey.OP_WRITE);
                return NioSocketActionType.OP_NULL;
            } else {
                int size = pl.getSize();
                int start = pl.getStart();
                for (int i = 0; i < size; i++) {
                    Host proxy = pl.get(start + i);
                    if (proxy != null) {
                        if (proxy.iPort > 0) {
                            SocketChannel to = SocketChannelHelper.connectNoBlock(proxy.sServer, proxy.iPort);
                            if (to != null) {
                                if (hReq.pl != null) {
                                    if (hReq.skChannel != null) {
                                        freeKeyAndSocket(hReq.skChannel);
                                        hReq.skChannel = null;
                                    }
                                }
                                hReq.pl = pl;
                                proxy.setResult(true);
                                Log.log(sResource + " " + proxy.sPath);
                                addPairSocketToRead(key, to);
                                return NioSocketActionType.OP_NULL;
                            } else {
                                proxy.setResult(false);
                                Log.logError(sResource + " " + proxy.sPath);
                            }
                        } else {
                            return toFileRequest(proxy, sResource, key, hReq, hRes);
                        }
                    } else {
                        break;
                    }
                }
            }
        }
        proxyManager.doCommand(sResource, hReq, hRes);

        hReq.toByteBuffer(hRes);
        return hReq.write(key);
    }

    private NioSocketActionType toFileRequest(Host proxy, String sResource, SelectionKey key, HttpProxyRequest hReq, HttpResponse hRes) {
        if (hReq.getHeaderValue("If-None-Match") != null) {
            hRes.setResponse(304);
        } else {
            String sFileName = proxy.getLocation(sResource);
            File file = new File(sFileName);
            if (file.exists() && file.isFile()) {
                hRes.addHeader(HttpHeader.csEtag, String.valueOf(file.lastModified()));
                hRes.setFileHeader(sFileName);
                long fileLen = file.length();
                if (proxy.isGZip && fileLen > iMinGZipLength) {
                    byte[] bData = FileHelper.readSmallFile(file);
                    if (bData != null) {
                        Log.log(sResource + " " + sFileName);
                        hRes.setBodyGZip(bData);
                    } else {
                        Log.logError(sResource + " " + sFileName);
                        hRes.setResponse(404);
                    }
                } else {
                    hRes.addHeader(csContentLength, String.valueOf(file.length()));
                    if (hReq.openFileChannel(sFileName)) {
                        Log.log(sResource + " " + sFileName);
                    } else {
                        Log.logError(sResource + " " + sFileName);
                        hRes.setResponse(404);
                    }
                }
            } else {
                Log.logError(sResource + " " + sFileName);
                hRes.setResponse(404);
            }
        }
        hReq.toByteBuffer(hRes);
        return hReq.write(key);
    }

    protected void initAndStart(int iLocalPort) {
        startAndBlock(iLocalPort, Runtime.getRuntime().availableProcessors() + 1, Runtime.getRuntime().availableProcessors() * 8 + 1);
    }

    public void refreshResourceMap(XmlNode cfg) {
        XmlNode resources = cfg.getChildNode("resources");
        if (resources != null) {
            ProxyManager lsMap = new ProxyManager();
            for (int i = 0; i < resources.getChildCount(); i++) {
                XmlNode resource = resources.getChildNode(i);
                if (resource != null && "resource".equals(resource.getTag())) {
                    String sPath = resource.getText();
                    String sUrl = resource.getAttrValue("url");
                    if (sUrl != null && sPath != null) {
                        String[] lsPath = StringAction.split(sPath, ';');
                        for (String path : lsPath) {
                            lsMap.add(sUrl, path);
                        }
                    }
                    Log.log(sUrl + ":" + sPath);
                }
            }
            proxyManager = lsMap;
        }
    }

    @Override
    public boolean doAction(XmlNode cfg, ProcessAction self) {
        int iLocalPort = StringAction.toInteger(cfg.getChildNodeText("localport"));
        Log.logClass("NginxServer start " + iLocalPort);
        refreshResourceMap(cfg);
        proxyManager.loadData();
        initAndStart(iLocalPort);
        Log.logClass("NginxServer stop");
        return true;
    }

    // http://127.0.0.1:30087/account/isregister?phone=18258448718
    static public void main(String[] args) {
		ProxyServer obj = new ProxyServer();
        obj.addResource("/index.html", "z:\\");
        obj.addResource("/", "http://20.21.1.133:30082");
        obj.initAndStart(8989);
    }
}