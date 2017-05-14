/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2013 Neil C Smith.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License,
 * or (at your option) any later version.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this work; if not, see http://www.gnu.org/licenses/
 * 
 *
 * Please visit http://neilcsmith.net if you need additional information or
 * have any questions.
 *
 */
package org.jaudiolibs.audioservers.jack;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jaudiolibs.audioservers.AudioClient;
import org.jaudiolibs.audioservers.AudioConfiguration;
import org.jaudiolibs.audioservers.AudioServer;
import org.jaudiolibs.audioservers.ext.ClientID;
import org.jaudiolibs.audioservers.ext.Connections;
import org.jaudiolibs.jnajack.Jack;
import org.jaudiolibs.jnajack.JackClient;
import org.jaudiolibs.jnajack.JackClientRegistrationCallback;
import org.jaudiolibs.jnajack.JackException;
import org.jaudiolibs.jnajack.JackOptions;
import org.jaudiolibs.jnajack.JackPort;
import org.jaudiolibs.jnajack.JackPortFlags;
import org.jaudiolibs.jnajack.JackPortRegistrationCallback;
import org.jaudiolibs.jnajack.JackPortType;
import org.jaudiolibs.jnajack.JackProcessCallback;
import org.jaudiolibs.jnajack.JackShutdownCallback;
import org.jaudiolibs.jnajack.JackStatus;

/**
 * Implementation of AudioServer using Jack (via JNAJack)
 *
 * @author Neil C Smith
 */
// @TODO check thread safety of client shutdown.
public class JackAudioServer implements AudioServer {

	private final static Logger LOG = Logger.getLogger(JackAudioServer.class.getName());

	private enum State {

		New, Initialising, Active, Closing, Terminated
	};

	private ClientID clientID;
	private AudioConfiguration context;
	private AudioClient client;
	protected Jack jack;
	protected JackClient jackclient;
	private AtomicReference<State> state;
	protected JackPort[] inputPorts;
	private List<FloatBuffer> inputBuffers;
	protected JackPort[] outputPorts;
	private List<FloatBuffer> outputBuffers;
	private Connections connections;

	private JackClientRegistrationCallback client_reg_callback;
	private JackPortRegistrationCallback port_reg_callback;

	public JackAudioServer(ClientID id, Connections connections, AudioConfiguration ctxt, AudioClient client) {
		this.clientID = id;
		this.connections = connections;
		this.context = ctxt;
		this.client = client;
		state = new AtomicReference<State>(State.New);
	}

	public void run() throws Exception {
		if (!state.compareAndSet(State.New, State.Initialising)) {
			throw new IllegalStateException();
		}
		try {
			initialise();
		} catch (Exception ex) {
			state.set(State.Terminated);
			closeAll();
			client.shutdown();
			throw ex;
		}
		if (state.compareAndSet(State.Initialising, State.Active)) {
			runImpl();
		}
		closeAll();
		client.shutdown();
		state.set(State.Terminated);
	}

	private void initialise() throws Exception {
		jack = Jack.getInstance();
		EnumSet<JackOptions> options = (connections.isConnectInputs() || connections.isConnectOutputs()) ? EnumSet.noneOf(JackOptions.class) : EnumSet.of(JackOptions.JackNoStartServer);
		EnumSet<JackStatus> status = EnumSet.noneOf(JackStatus.class);
		try {
			jackclient = jack.openClient(clientID.getIdentifier(), options, status);
		} catch (JackException ex) {
			LOG.log(Level.FINE, "Exception creating JACK client\nStatus set\n{0}", status);
			throw ex;
		}
		LOG.log(Level.FINE, "JACK client created\nStatus set\n{0}", status);
		int count = context.getInputChannelCount();
		inputPorts = new JackPort[count];
		inputBuffers = Arrays.asList(new FloatBuffer[count]);
		for (int i = 0; i < count; i++) {
			inputPorts[i] = jackclient.registerPort("Input_" + (i + 1), JackPortType.AUDIO, JackPortFlags.JackPortIsInput);
		}
		count = context.getOutputChannelCount();
		outputPorts = new JackPort[count];
		outputBuffers = Arrays.asList(new FloatBuffer[count]);
		for (int i = 0; i < count; i++) {
			outputPorts[i] = jackclient.registerPort("Output_" + (i + 1), JackPortType.AUDIO, JackPortFlags.JackPortIsOutput);
		}
		jackclient.setPortRegistrationCallback(new JackPortRegistrationCallback() {

			public void portUnregistered(JackClient client, String portFullName) {
				if (port_reg_callback != null) {
					port_reg_callback.portUnregistered(client, portFullName);
				}
			}

			public void portRegistered(JackClient client, String portFullName) {
				if (port_reg_callback != null) {
					port_reg_callback.portRegistered(client, portFullName);
				}
			}
		});
		jackclient.setClientRegistrationCallback(new JackClientRegistrationCallback() {

			public void clientUnregistered(JackClient invokingClient, String clientName) {
				if (client_reg_callback != null) {
					client_reg_callback.clientUnregistered(invokingClient, clientName);
				}
			}

			public void clientRegistered(JackClient invokingClient, String clientName) {
				if (client_reg_callback != null) {
					client_reg_callback.clientRegistered(invokingClient, clientName);
				}
			}
		});
	}

	protected void runImpl() {
		try {
			// make sure context is correct.
			ClientID id = clientID;
			String actualID = jackclient.getName();
			if (!id.getIdentifier().equals(actualID)) {
				id = new ClientID(actualID);
			}
			context = new AudioConfiguration(jackclient.getSampleRate(), inputPorts.length, outputPorts.length, jackclient.getBufferSize(), id, connections, jackclient);
			LOG.log(Level.FINE, "Configuring AudioClient\n{0}", context);
			client.configure(context);
			jackclient.setProcessCallback(new Callback());
			jackclient.onShutdown(new ShutDownHook());
			jackclient.activate();
			if (connections.isConnectInputs()) {
				connectInputs();
			}
			if (connections.isConnectOutputs()) {
				connectOutputs();
			}
			synchronized(this){
				while(state.get() == State.Active){
					wait();
				}
			}
		} catch (Exception ex) {
			LOG.log(Level.FINE, "", ex);
			shutdown();
		}
	}

	
	private void connectInputs() {
		try {
			String[] ins = jack.getPorts(jackclient, null, JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsOutput, JackPortFlags.JackPortIsPhysical));
			int inCount = Math.min(ins.length, inputPorts.length);
			for (int i = 0; i < inCount; i++) {
				jack.connect(jackclient, ins[i], inputPorts[i].getName());
			}
		} catch (JackException ex) {
			Logger.getLogger(JackAudioServer.class.getName()).log(Level.SEVERE, null, ex);
		}

	}

	private void connectOutputs() {
		try {
			String[] outs = jack.getPorts(jackclient, null, JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsInput, JackPortFlags.JackPortIsPhysical));
			int outCount = Math.min(outs.length, outputPorts.length);
			for (int i = 0; i < outCount; i++) {
				jack.connect(jackclient, outputPorts[i].getName(), outs[i]);
			}
		} catch (JackException ex) {
			Logger.getLogger(JackAudioServer.class.getName()).log(Level.SEVERE, null, ex);
		}

	}

	private void processBuffers(int nframes) {
		for (int i = 0; i < inputPorts.length; i++) {
			inputBuffers.set(i, inputPorts[i].getFloatBuffer());
		}
		for (int i = 0; i < outputPorts.length; i++) {
			outputBuffers.set(i, outputPorts[i].getFloatBuffer());

		}
		client.process(System.nanoTime(), inputBuffers, outputBuffers, nframes);
	}

	private class Callback implements JackProcessCallback {

		public boolean process(JackClient client, int nframes) {
			if (state.get() != State.Active) {
				return false;
			} else {
				try {
					processBuffers(nframes);
					return true;
				} catch (Exception ex) {
					shutdown();
					return false;
				}

			}
		}
	}

	private class ShutDownHook implements JackShutdownCallback {

		public void clientShutdown(JackClient client) {
			shutdown();
		}
	}

	public AudioConfiguration getAudioContext() {
		return context;
	}

	public boolean isActive() {
		State st = state.get();
		return (st == State.Active || st == State.Closing);
	}

	public void shutdown() {
		State st;
		do {
			st = state.get();
			if (st == State.Terminated || st == State.Closing) {
				break;
			}
		} while (!state.compareAndSet(st, State.Closing));
	}

	private void closeAll() {
		try {
			jackclient.close();
		} catch (Throwable t) {
		}
	}

	public void connectPort(String source, String destination) throws JackException {
		jack.connect(jackclient, source, destination);
	}

	public void disconnectPort(String source, String destination) throws JackException {
		jack.disconnect(jackclient, source, destination);
	}

	public void setClientRegistrationCallback(JackClientRegistrationCallback callback){
		client_reg_callback = callback;
	}

	public void setPortRegistrationCallback(JackPortRegistrationCallback callback){
		port_reg_callback = callback;
	}

}
