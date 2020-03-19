package com.china.fortune.myant;

import com.china.fortune.file.ReadLinesInteface;
import com.china.fortune.global.Log;
import com.china.fortune.string.StringAction;

public class GetCpuAndMem {
	private ReadLinesInteface rli = new ReadLinesInteface() {
		private StringBuilder sb = new StringBuilder();
		@Override
		public boolean onRead(String s) {
			if (s.contains("Cpu")) {
				sb.setLength(0);
			}
			sb.append(s);
			if (s.contains("KiB Swap")) {
				String sLine = sb.toString();
				String sCpu = StringAction.findBetween(sLine, "Cpu(s)", "KiB Mem");
				String sCpuUS = StringAction.findBetween(sCpu, ":", "us");
				String KiBMem = StringAction.findBetween(sLine, "KiB Mem", "KiB Swap");
				String sTotal = StringAction.findBetween(KiBMem, ":", "total");
				String sFree = StringAction.findBetween(KiBMem, "total", "free");
				int iTotal = StringAction.toInteger(sTotal);
				int iFree = StringAction.toInteger(sFree);
				int iMemPercent = (iTotal - iFree) * 100 / iTotal; 
				int iCpu = StringAction.toInteger(sCpuUS);
				Log.log("Cpu:" + iCpu + "% Mem:" + iMemPercent + "%");
				
				return false;
			}
			return true;
		}
	};
	
	private void getCpuAndMem() {
		MyAntCommand mac = new MyAntCommand("121.40.112.2", 9000, rli);
//		mac.sendCommandAndRecvMore("run ./taillog.sh");
		mac.sendCommand("run top -b -n 1");
		mac.sendCommand("exit");
		mac.close();
	}
	
	public static void main(String[] args) {
		GetCpuAndMem tt = new GetCpuAndMem();
		tt.getCpuAndMem();
	}
}
