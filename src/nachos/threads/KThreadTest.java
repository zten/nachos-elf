package nachos.threads;

import nachos.machine.*;
import nachos.threads.*;
// A Tester for the join() implementation.
public class KThreadTest {
	// LoopThread class, which implements a KThread that 
	//simply prints out numbers in sequence.
	private static class LoopThread implements Runnable {
		LoopThread(String name, int rounds) {
			this.name = name;
			this.rounds = rounds;
		}
		public void run() {
			for (int i = 0; i < Integer.MAX_VALUE; i++);
			
			for (int i=0; i<rounds; i++) {
				System.out.println(name + " looped " + i + " times");
				KThread.yield();
			}
			System.out.println(name + " done");
		}
		private String name;
		private int rounds;
	}    
	//  JoinThread class, which implements a KThread  
	// that attempts to join with one or two threads, in sequence.
	private static class JoinThread implements Runnable {
		JoinThread(String name, KThread thread1, KThread thread2) {
			this.name = name;
			this.thread1 = thread1;
			this.thread2 = thread2;
		}
		public void run() {
			if (thread1 != null) {
				System.out.println("*** " + name + " joining with" + 
						thread1.toString());
				thread1.join();
				System.out.println("*** " + name +" joined with " + 
						thread1.toString());
			}            
			if (thread2 != null) {
				System.out.println("*** " + name + " joining with " + 
						thread2.toString());

				thread2.join();
				System.out.println("*** " + name + " joined with " + 
						thread2.toString());
			}          
			System.out.println("*** "+name+" done.");
		}
		private String name;
		private KThread thread1;
		private KThread thread2;
	} 
	// Test itself.
	public static void runTest() {
		System.out.println("**** KThread Join test START ****");
		/* Create 3 LoopThread */
		KThread loopThreads[] = new KThread[3];
		for (int i=0; i < 3; i++) {
			loopThreads[i] = new KThread(new LoopThread("LT"+(i+1)
					,(i+2)));
			loopThreads[i].setName("LT"+(i+1));
			loopThreads[i].fork();
		}
		/* Create 2 JoinThreads that waits for loopThreads */
		KThread joinThread1 = new KThread(new JoinThread("JT1",
				loopThreads[0],loopThreads[1]));
		joinThread1.setName("JT1");
		joinThread1.fork();
		KThread joinThread2 = new KThread(new JoinThread("JT2",
				joinThread1,loopThreads[2]));
		joinThread2.setName("JT2");
		joinThread2.fork();
		
		/* Join with all the above */
		for (int i=0; i < 3; i++) {
			loopThreads[i].join();
		}
		joinThread1.join();
		joinThread2.join();

		System.out.println("**** KThread Join test FINISHED ****");
	}    
}
