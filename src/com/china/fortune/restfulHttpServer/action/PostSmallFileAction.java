package com.china.fortune.restfulHttpServer.action;

import com.china.fortune.file.FileUtils;
import com.china.fortune.global.Log;
import com.china.fortune.http.httpHead.HttpResponse;
import com.china.fortune.http.server.HttpServerRequest;
import com.china.fortune.http.webservice.servlet.ServletInterface;
import com.china.fortune.os.file.PathUtils;

// small than 10K
public class PostSmallFileAction implements ServletInterface {
	private String sRootPath = PathUtils.getCurrentDataPath(false);
	
	@Override
	public RunStatus doAction(HttpServerRequest hReq, HttpResponse hRes, Object objForThread) {
		String sName = hReq.getResourceWithoutParam();
		Log.logClass(sName);
		String sFile = sRootPath + sName;	
		byte[] bData = hReq.getByteBody();
		FileUtils.writeSmallFile(sFile, bData);
		return RunStatus.isOK;
	}

	@Override
	public ServletInterface getHost() {
		return this;
	}

}
