package nachos.threads;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

import nachos.machine.*;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 * 
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	
	static char debugFlag = 'A';

	public Alarm() {
		this.waitQueue = new PriorityQueue<AlarmThread>();
		this.waitQueueLock = new Lock();
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {
		// Lock to prevent concurrent modification of the alarm queue
		boolean intStatus = Machine.interrupt().disable();
		waitQueueLock.acquire();
		while (!waitQueue.isEmpty()) {
			if (waitQueue.peek().getTime() <= Machine.timer().getTime()) {
				Lib.debug(debugFlag, "Requeueing " + waitQueue.peek() + ", current time " + Machine.timer().getTime());
				waitQueue.poll().getThread().ready();
			} else {
				break;
			}
		}
		waitQueueLock.release();
		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 * 
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 * 
	 * @param x
	 *            the minimum number of clock ticks to wait.
	 * 
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
		long wakeTime = Machine.timer().getTime() + x;

		/*
		 * It might be possible, with a short enough wait time, for a thread to
		 * enqueue itself, get interrupted, and cause the Alarm handler to try
		 * to wake it up before this method returns -- this would probably do
		 * bad things, since by the time this thread regains control, it would
		 * put itself to sleep(), never to wake up again.
		 */
		boolean intStatus = Machine.interrupt().disable();
		waitQueueLock.acquire();
		waitQueue.offer(new AlarmThread(wakeTime, KThread.currentThread()));
		waitQueueLock.release();
		KThread.sleep();
		Machine.interrupt().restore(intStatus);
	}

	// Nachos convention seems to place member fields at the bottom of the
	// source code
	private Queue<AlarmThread> waitQueue;
	private Lock waitQueueLock;

	/**
	 * Object designed for the alarm's priority queue, which stores
	 * threads that are sleeping until a specific time in the future.
	 * 
	 * @author Christopher Childs, Eric Muehlberg, Vaishal Shah
	 *
	 */
	class AlarmThread implements Comparable<AlarmThread> {
		private KThread thread;
		private long time;

		public AlarmThread(long time, KThread thread) {
			this.time = time;
			this.thread = thread;
		}

		public long getTime() {
			return this.time;
		}

		public KThread getThread() {
			return this.thread;
		}

		public int compareTo(AlarmThread o) {
			long otherTime = o.getTime();
			if (this.time == otherTime)
				return 0;
			else if (this.time < otherTime) 
				// a sooner time should have greater priority
				return -1;
			else
				return 1;
		}
		
		public String toString() {
			return "waitTime: " + this.time;
		}

	}
}
