package im.composer.audioservers.asio;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.jaudiolibs.audioservers.AudioClient;
import org.jaudiolibs.audioservers.AudioConfiguration;
import org.jaudiolibs.audioservers.AudioServer;

import com.synthbot.jasiohost.AsioChannel;
import com.synthbot.jasiohost.AsioDriver;
import com.synthbot.jasiohost.AsioDriverListener;

import static com.synthbot.jasiohost.AsioDriverState.*;

/**
 * Implementation of AudioServer using ASIO (via jasiohost.dll)
 * 
 * @author David Zhang (zdl@zdl.hk)
 * @since 2013-4-14
 * 
 */
public class AsioAudioServer implements AudioServer {

	private final AsioDriver asioDriver;
	private AudioConfiguration audioContext;
	private final AudioClient client;
	private AsioDriverListener driverListener;
	private final HashSet<AsioChannel> activeChannels;
	private Object running_signal = new Object();
	private int inputLatency = Integer.MAX_VALUE;
	private int outputLatency = Integer.MAX_VALUE;

	private AsioAudioServer(AsioDriver asioDriver, AudioConfiguration audioContext, AudioClient client, HashSet<AsioChannel> activeChannels) {
		super();
		this.asioDriver = asioDriver;
		this.audioContext = audioContext;
		this.client = client;
		this.activeChannels = activeChannels;
	}

	@Override
	public void run() throws Exception {
		initialise();
		runImpl();
		if (asioDriver != null) {
			shutdown();
		}
	}

	private void initialise() throws Exception {
		driverListener = new AsioAudioClientAdapter(client);
		asioDriver.addAsioDriverListener(driverListener);
		asioDriver.setSampleRate(getAudioContext().getSampleRate());
		setBufferSize(asioDriver.getBufferPreferredSize());
		client.configure(getAudioContext());
		asioDriver.createBuffers(activeChannels);
		asioDriver.start();
	}

	private void runImpl() {
		while (isActive()) {
			try {
				running_signal.wait();
			} catch (InterruptedException e) {
				continue;
			}
		}
	}

	@Override
	public AudioConfiguration getAudioContext() {
		return audioContext;
	}

	public void setAudioContext(AudioConfiguration audioContext) {
		this.audioContext = audioContext;
	}

	private void setBufferSize(int bufz) {
		AudioConfiguration conf = getAudioContext();
		conf = new AudioConfiguration(conf.getSampleRate(), conf.getInputChannelCount(), conf.getOutputChannelCount(), bufz, false);
		setAudioContext(conf);
	}

	public int getInputLatency() {
		return inputLatency;
	}

	public int getOutputLatency() {
		return outputLatency;
	}

	@Override
	public boolean isActive() {
		if (!AsioDriver.isDriverLoaded() || asioDriver == null) {
			return false;
		}
		return asioDriver.getCurrentState() == RUNNING;
	}

	@Override
	public void shutdown() {
		asioDriver.returnToState(LOADED);
		asioDriver.removeAsioDriverListener(driverListener);
		asioDriver.shutdownAndUnloadDriver();
		running_signal.notifyAll();
		// asioDriver = null;
	}

    public static final AudioServer createServer(AudioConfiguration config, AudioClient client) throws Exception {
    	List<String> names = AsioDriver.getDriverNames();
    	if(names.isEmpty()){
    		throw new IndexOutOfBoundsException("No ASIO Device Available!");
    	}
    	String id = names.get(0);
    	AsioDriver asioDriver = AsioDriver.getDriver(id);
    	if(config.getInputChannelCount()>asioDriver.getNumChannelsInput()){
    		throw new IndexOutOfBoundsException("Not Enought Input Channels!");
    	}
    	if(config.getOutputChannelCount()>asioDriver.getNumChannelsOutput()){
    		throw new IndexOutOfBoundsException("Not Enought Output Channels!");
    	}
    	Set<Integer> inputChannel = new TreeSet<>();
    	Set<Integer> outputChannel = new TreeSet<>();
    	for(int i=0;i<config.getInputChannelCount();i++){
    		inputChannel.add(i);
    	}
    	for(int i=0;i<config.getOutputChannelCount();i++){
    		outputChannel.add(i);
    	}
    	return create(id,config.getSampleRate(),inputChannel,outputChannel,client);
    }
	public static final AsioAudioServer create(String id, float sampleRate, Set<Integer> inputChannel, Set<Integer> outputChannel, AudioClient client) {
		HashSet<AsioChannel> activeChannels = new HashSet<AsioChannel>();
		AsioDriver asioDriver = AsioDriver.getDriver(id);
		if (inputChannel != null) {
			for (int i : inputChannel) {
				activeChannels.add(asioDriver.getChannelInput(i));
			}
		}
		if (outputChannel != null) {
			for (int i : outputChannel) {
				activeChannels.add(asioDriver.getChannelOutput(i));
			}
		}
		AudioConfiguration ctxt = new AudioConfiguration(sampleRate, inputChannel.size(), outputChannel.size(), asioDriver.getBufferPreferredSize(), false);
		return new AsioAudioServer(asioDriver, ctxt, client, activeChannels);
	}

	private final class AsioAudioClientAdapter implements AsioDriverListener {
		private final AudioClient client;
		private int bufz = getAudioContext().getMaxBufferSize();

		private AsioAudioClientAdapter(AudioClient client) {
			this.client = client;
		}

		@Override
		public void sampleRateDidChange(double sampleRate) {
			AudioConfiguration conf = getAudioContext();
			conf = new AudioConfiguration((float) sampleRate, conf.getInputChannelCount(), conf.getOutputChannelCount(), conf.getMaxBufferSize(), false);
			setAudioContext(conf);
			try {
				client.configure(conf);
			} catch (Exception e) {
			}
		}

		@Override
		public void resetRequest() {

			/*
			 * This thread will attempt to shut down the ASIO driver. However,
			 * it will block on the AsioDriver object at least until the current
			 * method has returned.
			 */
			new Thread() {
				@Override
				public void run() {
					asioDriver.returnToState(INITIALIZED);
				}
			}.start();
		}

		@Override
		public void resyncRequest() {

		}

		@Override
		public void bufferSizeChanged(int bufferSize) {
			bufz = bufferSize;
			setBufferSize(bufferSize);
			try {
				client.configure(getAudioContext());
			} catch (Exception e) {
			}
		}

		@Override
		public void latenciesChanged(int inputLatency, int outputLatency) {
			AsioAudioServer.this.inputLatency = inputLatency;
			AsioAudioServer.this.outputLatency = outputLatency;
		}

		@Override
		public void bufferSwitch(long sampleTime, long samplePosition, Set<AsioChannel> activeChannels) {
			List<FloatBuffer> inputs = new ArrayList<>(getAudioContext().getInputChannelCount());
			List<FloatBuffer> outputs = new ArrayList<>(getAudioContext().getOutputChannelCount());
			List<AsioChannel> output_channels = null;
			for (AsioChannel channel : activeChannels) {
				if (channel.isInput()) {
					float[] data = new float[bufz];
					channel.read(data);
					FloatBuffer fbuz = FloatBuffer.wrap(data);
					inputs.add(fbuz);
				} else {
					if (output_channels == null) {
						output_channels = new ArrayList<>(getAudioContext().getOutputChannelCount());
					}
					FloatBuffer fbuz = FloatBuffer.allocate(bufz);
					outputs.add(fbuz);
					output_channels.add(channel);
				}
			}
			client.process(sampleTime, inputs, outputs, bufz);
			for (int i = 0; i < outputs.size(); i++) {
				FloatBuffer fbuz = outputs.get(i);
				AsioChannel channel = output_channels.get(i);
				float[] data = fbuz.array();
				channel.write(data);
			}
		}
	}
}
