package nachos.threads;

import nachos.machine.Lib;
import nachos.machine.Machine;
import java.util.Random;

public class AlarmTest {
	static char debugFlag = 'A';
	
	private static class AlarmThread implements Runnable {
		long waitTime;
		
		AlarmThread(long waitTime) {
			this.waitTime = waitTime;
		}
		
		public void run() {
			for (int i = 0; i < 40; i++) {
				long wait = Machine.timer().getTime() + this.waitTime;
				Lib.debug(debugFlag, "Loop " + i + ", Current time: " + Machine.timer().getTime() + " / Sleeping until at least " + wait);
				ThreadedKernel.alarm.waitUntil(waitTime);
				Lib.debug(debugFlag, "Loop " + i + ", Wait time " + wait + " -- Woken up at " + Machine.timer().getTime());

			}
		}
	}
	
	public static void runTest() {
		Random rg = new Random();
		KThread testThreads[] = new KThread[100];
		
		for (int i = 0; i < testThreads.length; i++) {
			testThreads[i] = new KThread(new AlarmThread(rg.nextInt(50000)));
			testThreads[i].fork();
		}
		
		Lib.debug(debugFlag, "ThreadedKernel waiting for 3000000 nachos ticks");
		ThreadedKernel.alarm.waitUntil(3000000);
		Lib.debug(debugFlag, "ThreadedKernel awoken");
	}
}
