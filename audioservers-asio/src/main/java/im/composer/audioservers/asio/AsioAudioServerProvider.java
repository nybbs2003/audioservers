package im.composer.audioservers.asio;

import org.jaudiolibs.audioservers.AudioClient;
import org.jaudiolibs.audioservers.AudioConfiguration;
import org.jaudiolibs.audioservers.AudioServer;
import org.jaudiolibs.audioservers.AudioServerProvider;

/**
 * Implementation of AudioServerProvider using ASIO (via jasiohost.dll)
 * 
 * @author David Zhang (zdl@zdl.hk)
 * @since 2016-11-4
 * 
 */
public class AsioAudioServerProvider extends AudioServerProvider {

	@Override
	public String getLibraryName() {
		return "ASIO";
	}

	@Override
	public AudioServer createServer(AudioConfiguration config, AudioClient client) throws Exception {
		return AsioAudioServer.createServer(config, client);
	}

}
