package nachos.threads;

import nachos.machine.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>, and multiple
 * threads can be waiting to <i>listen</i>. But there should never be a time
 * when both a speaker and a listener are waiting, because the two threads can
 * be paired off at this point.
 */
public class Communicator {
	Lock communicatorLock;
	Condition waitingToSpeak, waitingToListen;
	Condition listenerCondition, speakerCondition;
	boolean hasSpeaker, hasListener, listened, spoke;
	int word;
	static char debugFlag = 'R';
	static Random rg = new Random();

	/**
	 * Allocate a new communicator.
	 */
	public Communicator() {
		communicatorLock = new Lock();
		waitingToSpeak = new Condition(communicatorLock);
		waitingToListen = new Condition(communicatorLock);
		listenerCondition = new Condition(communicatorLock);
		speakerCondition = new Condition(communicatorLock);
		hasSpeaker = false;
		hasListener = false;
		listened = false;
		spoke = false;
	}

	/**
	 * Wait for a thread to listen through this communicator, and then transfer
	 * <i>word</i> to the listener.
	 * 
	 * <p>
	 * Does not return until this thread is paired up with a listening thread.
	 * Exactly one listener should receive <i>word</i>.
	 * 
	 * @param word
	 *            the integer to transfer.
	 */
	public void speak(int word) {
		Lib.debug(debugFlag, "Speaker entering");
		communicatorLock.acquire();
		Lib.debug(debugFlag, "Speaker locked");
		while (hasSpeaker) {
			Lib.debug(debugFlag, "sleeping, we have a speaker");
			waitingToSpeak.sleep();
		}
		Lib.debug(debugFlag, "Speaker awoken");
		// if we're awake at this point, hasSpeaker is false
		hasSpeaker = true;
		listened = false;
		// copy the word
		this.word = word;
		spoke = true;
		Lib.debug(debugFlag, "speaker just spoke");
		listenerCondition.wake();
		// anybody listening?
		while (!listened) {
			Lib.debug(debugFlag, "No listener");
			waitingToListen.wake();
			speakerCondition.sleep();
			Lib.debug(debugFlag, "Woken, rechecking listened");
		}
		Lib.debug(debugFlag, "speaker just woke, listener acknowledged");
		// yes, this seems TOTALLY INSANE but it works
		listenerCondition.wake();
		speakerCondition.sleep();
		listenerCondition.wake();
		// postcondition: there's no longer a speaker when this exits.
		hasSpeaker = false;
		listened = false;
		spoke = false;
		Lib.debug(debugFlag, "Speaker now concluding");
		waitingToSpeak.wake();
		communicatorLock.release();
	}

	/**
	 * Wait for a thread to speak through this communicator, and then return the
	 * <i>word</i> that thread passed to <tt>speak()</tt>.
	 * 
	 * @return the integer transferred.
	 */
	public int listen() {
		int word;
		Lib.debug(debugFlag, "Listener entering");
		communicatorLock.acquire();
		Lib.debug(debugFlag, "Listener locked");
		while (hasListener) {
			Lib.debug(debugFlag, "sleeping, we have a listener");
			waitingToListen.sleep();
		}
		Lib.debug(debugFlag, "listener woken up");
		hasListener = true;
		// awake: no listener, so let's set our guard
		listened = false;
		speakerCondition.wake();
		// anything to receive?
		while (!spoke) {
			Lib.debug(debugFlag, "nothing spoken");
			waitingToSpeak.wake();
			listenerCondition.sleep();
		}
		word = this.word;
		Lib.debug(debugFlag, "listener has copied word and is setting listened");
		listened = true;
		Lib.debug(debugFlag, "attempting to wake the speaker");
		// similar insanity over here -- the exact opposite to speak()
		speakerCondition.wake();
		listenerCondition.sleep();
		speakerCondition.wake();
		hasListener = false;
		spoke = false;
		waitingToListen.wake();
		Lib.debug(debugFlag, "Listener now concluding");
		communicatorLock.release();

		return word;
	}

	public static void selfTest() {
	}

	/**
	 * Exercise the Communicator class
	 * 
	 * @author Christopher Childs
	 * 
	 */
	private static class CommunicatorTest {

		private static class Rendezvous implements Runnable {
			int id;
			boolean speaker;
			Communicator test;
			Communicator finish;

			Rendezvous(int id, boolean speaker, Communicator test,
					Communicator finish) {
				this.id = id;
				this.speaker = speaker;
				this.test = test;
				this.finish = finish;
			}

			public void run() {
				boolean swapSpeaker = false;
				for (int i = 0; i < 5; i++) {
					if (!swapSpeaker) {
						if (speaker) {
							int speaking = rg.nextInt(1000);
							Lib.debug(debugFlag, "Speaker (id " + id
									+ ") speaking " + speaking);
							test.speak(speaking);
							Lib.debug(debugFlag, "Speaker (id " + id
									+ ") finished speaking");

						} else {
							int listening = test.listen();
							Lib.debug(debugFlag, "Listener (id " + id
									+ ") heard " + listening);

						}
					} else {
						if (speaker) { // really listener
							int listening = test.listen();
							Lib.debug(debugFlag, "Listener (id " + id
									+ ") heard " + listening);
						} else {
							int speaking = rg.nextInt(1000);
							Lib.debug(debugFlag, "Speaker (id " + id
									+ ") speaking " + speaking);
							test.speak(speaking);
							Lib.debug(debugFlag, "Speaker (id " + id
									+ ") finished speaking");
						}
					}
					ThreadedKernel.alarm.waitUntil(rg.nextInt(5000));
				}
				// test over
				finish.speak(id);
			}
		}

		public static void runTest() {
			KThread threads[] = new KThread[50];

			Communicator test = new Communicator();
			Communicator finish = new Communicator();
			Set<Integer> outstanding = new HashSet<Integer>();

			for (int i = 0; i < threads.length; i++) {
				outstanding.add(i);
				if (i % 2 == 0) {
					threads[i] = new KThread(new Rendezvous(i, true, test,
							finish));
				} else {
					threads[i] = new KThread(new Rendezvous(i, false, test,
							finish));
				}
				threads[i].fork();
			}

			int l = threads.length;
			for (int j = 0; j < threads.length; j++) {
				for (Integer k : outstanding) {
					System.out.printf("%d ", k);
				}
				System.out.println();
				int listened = finish.listen();
				outstanding.remove(listened);
				--l;
				Lib.debug(debugFlag, "Rendezvous " + listened + " finished ("
						+ l + " outstanding)");
			}
		}
	}
}
