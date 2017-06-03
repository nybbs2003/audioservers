package org.jaudiolibs.audioservers.jack;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Transmitter;

import org.jaudiolibs.jnajack.Jack;
import org.jaudiolibs.jnajack.JackClient;
import org.jaudiolibs.jnajack.JackException;
import org.jaudiolibs.jnajack.JackMidi;
import org.jaudiolibs.jnajack.JackOptions;
import org.jaudiolibs.jnajack.JackPort;
import org.jaudiolibs.jnajack.JackPortFlags;
import org.jaudiolibs.jnajack.JackPortType;
import org.jaudiolibs.jnajack.JackProcessCallback;
import org.jaudiolibs.jnajack.JackStatus;

public class JackMidiDevice implements MidiDevice, JackProcessCallback {

	private static final ExecutorService es = Executors.newCachedThreadPool((r) -> {
		Thread t = new Thread(r);
		t.setDaemon(true);
		return t;
	});
	private Queue<MidiMessage> outs;
	private final String deviceName;
	private final boolean hasInput, hasOutput;
	private JackClient client;
	private JackPort inputPort, outputPort;
	private JackMidi.Event midiEvent;
	private MyTransmitter trans = null;

	public JackMidiDevice(String deviceName, boolean hasInput, boolean hasOutput) {
		this.deviceName = deviceName;
		this.hasInput = hasInput;
		this.hasOutput = hasOutput;
	}

	public Info getDeviceInfo() {
		return null;
	}

	public void open() throws MidiUnavailableException {
		try {
			Jack jack = Jack.getInstance();
			client = jack.openClient(deviceName, EnumSet.of(JackOptions.JackNoStartServer), EnumSet.noneOf(JackStatus.class));
			if (hasInput) {
				inputPort = client.registerPort("MIDI in", JackPortType.MIDI, JackPortFlags.JackPortIsInput);
			}
			if (hasOutput) {
				outputPort = client.registerPort("MIDI out", JackPortType.MIDI, JackPortFlags.JackPortIsOutput);
				outs = new LinkedList<>();
			}
			midiEvent = new JackMidi.Event();
			client.setProcessCallback(this);
			client.activate();
		} catch (JackException e) {
			throw new MidiUnavailableException();
		}

	}

	public void close() {
		if (client != null) {
			client.deactivate();
			client.close();
			client = null;
		}
	}

	public boolean isOpen() {
		return client != null;
	}

	public long getMicrosecondPosition() {
		return -1;
	}

	public int getMaxReceivers() {
		return hasOutput ? -1 : 0;
	}

	public int getMaxTransmitters() {
		return hasInput ? 1 : 0;
	}

	public Receiver getReceiver() throws MidiUnavailableException {
		if (!hasOutput) {
			throw new MidiUnavailableException();
		}
		return new Receiver() {

			public void send(MidiMessage message, long timeStamp) {
				outs.offer(message);
			}

			public void close() {

			}
		};
	}

	public List<Receiver> getReceivers() {
		return Arrays.asList();
	}

	public synchronized Transmitter getTransmitter() throws MidiUnavailableException {
		if (trans == null) {
			trans = new MyTransmitter();
			es.submit(trans);
		}
		return trans;
	}

	public List<Transmitter> getTransmitters() {
		if (trans != null && trans.open) {
			Arrays.asList(trans);
		}
		return Arrays.asList();
	}

	@Override
	public boolean process(JackClient client, int nframes) {
		if (trans != null && trans.open) {
			try {
				int eventCount = JackMidi.getEventCount(inputPort);
				for (int i = 0; i < eventCount; ++i) {
					JackMidi.eventGet(midiEvent, inputPort, i);
					int size = midiEvent.size();
					byte[] data = new byte[size];
					midiEvent.read(data);
					trans.q.offer(data);
				}
				return true;
			} catch (JackException ex) {
				System.out.println("ERROR : " + ex);
				return false;
			}
		}
		if (outputPort != null && outs != null) {
			try {
				JackMidi.clearBuffer(outputPort);
				MidiMessage msg;
				while ((msg = outs.poll())!=null) {
					JackMidi.eventWrite(outputPort, 0, msg.getMessage(), msg.getLength());
				}
			} catch (JackException e) {
			}
		}
		return true;
	}

	private final class MyTransmitter implements Transmitter, Callable<Void> {
		private BlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
		private Receiver receiver;
		private boolean open;

		public Receiver getReceiver() {
			return receiver;
		}

		public void setReceiver(Receiver receiver) {
			this.receiver = receiver;
		}

		@Override
		public Void call() throws Exception {
			open = true;
			while (open) {
				byte[] data = q.take();
				if (receiver != null && data != null && data.length > 0) {
					MyMidiMessage msg = new MyMidiMessage(data);
					receiver.send(msg, -1);
				}
			}
			return null;
		}

		@Override
		public void close() {
			open = false;
		}

	}

	private static final class MyMidiMessage extends MidiMessage {

		protected MyMidiMessage(byte[] data) {
			super(data);
		}

		@Override
		public MyMidiMessage clone() {
			return new MyMidiMessage(data);
		}

	}
}
