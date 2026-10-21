package com.planet_ink.coffee_mud.application;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;

import com.planet_ink.coffee_mud.Common.interfaces.Session;
import com.planet_ink.coffee_mud.core.*;
import com.planet_ink.coffee_mud.core.interfaces.*;
/*
Copyright 2026 CoffeeMudFork contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
/**
 * CoffeeMud Application entry point class for playing alone in a single
 * terminal window. It boots the normal MUD engine with the network-facing
 * extras turned off (web, smtp, intermud), listening only on the local
 * machine, then connects to it from inside the same process and pipes the
 * game to and from this console. When the player quits, or the console
 * input ends, the game is saved and shut down cleanly.
 *
 * Any arguments are passed through to MUD.main, so BOOT=myini.ini or
 * KEY=VALUE overrides work the same way they do there, and win over the
 * solo-mode defaults.
 *
 * Server log output is redirected to solo.log so it doesn't clutter the
 * game.
 */
public class SoloMUD
{
	private static final PrintStream console = System.out;

	private static final String LOG_FILE = "solo.log";

	private static final String MCP_PREFIX = "#$#";

	private static volatile Socket currentSock = null;

	private static volatile boolean savingStarted = false;

	private static Thread keyboardThread = null;

	/**
	 * Application entry point for playing alone.
	 *
	 * @param args MUD.main arguments: optional mud name, BOOT=, KEY=VALUE
	 */
	public static void main(final String[] args)
	{
		final int port = findFreeLocalPort();
		if(port <= 0)
		{
			console.println("Unable to find a free local port to run the game on.");
			System.exit(1);
			return;
		}

		final List<String> mudArgs = new ArrayList<String>();
		mudArgs.add("BIND=127.0.0.1");
		mudArgs.add("PORT="+port);
		mudArgs.add("RUNWEBSERVERS=false");
		mudArgs.add("RUNSMTPSERVER=false");
		mudArgs.add("RUNI3SERVER=false");
		mudArgs.add("RUNIMC2CLIENT=false");
		mudArgs.add("RUNCM1SERVER=false");
		mudArgs.add("RUNGVSERVER=false");
		// later values win, so the player's own overrides come last
		mudArgs.addAll(Arrays.asList(args));

		try
		{
			final PrintStream logStream = new PrintStream(new FileOutputStream(LOG_FILE, false), true);
			System.setOut(logStream);
			System.setErr(logStream);
		}
		catch(final IOException e)
		{
			console.println("Unable to open "+LOG_FILE+": "+e.getMessage());
			System.exit(1);
			return;
		}

		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				// Ctrl+C: MUD's own shutdown hook does the actual saving
				if(!savingStarted)
					console.println(System.lineSeparator()+"Saving the game and shutting down...");
			}
		}, "SoloShutdownNotice"));

		console.println("CoffeeMud solo mode");
		console.println("Starting the game. This can take a minute; server messages go to "+LOG_FILE+".");
		final Thread mudThread = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				MUD.main(mudArgs.toArray(new String[0]));
			}
		}, "MUDMain");
		mudThread.start();

		boolean everStarted = false;
		while(waitForStartup(mudThread))
		{
			everStarted = true;
			if(playOnConsole() && isShuttingDown())
			{
				// shut down or restarting from inside the game; wait and see which
				console.println();
				console.println("The game is shutting down...");
				continue;
			}
			shutdownMud();
			break;
		}

		if(!everStarted)
			console.println("The game failed to start. See "+LOG_FILE+" and the mud logs for details.");
		try
		{
			mudThread.join(30000);
		}
		catch(final InterruptedException e)
		{
		}
		console.println("Goodbye!");
		savingStarted = true;
		System.exit(everStarted ? 0 : 1);
	}

	/**
	 * Asks the OS for an unused port on the loopback interface, so that solo
	 * mode never collides with another running mud.
	 *
	 * @return the port number, or -1
	 */
	private static int findFreeLocalPort()
	{
		try(ServerSocket S = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
		{
			return S.getLocalPort();
		}
		catch(final IOException e)
		{
			return -1;
		}
	}

	/**
	 * Returns the properties of the main mud host, or null if it's not
	 * booted far enough to have any.
	 *
	 * @return the main host props, or null
	 */
	private static CMProps mainProps()
	{
		return CMProps.instance(MudHost.MAIN_HOST);
	}

	/**
	 * Returns true if the main host is shutting down.
	 *
	 * @return true if shutting down
	 */
	private static boolean isShuttingDown()
	{
		final CMProps props = mainProps();
		return (props != null) && props._isState(CMProps.HostState.SHUTTINGDOWN);
	}

	/**
	 * Waits until every mud host is accepting connections, showing the boot
	 * status as it goes.
	 *
	 * @param mudThread the thread running MUD.main
	 * @return true if the game is up, false if MUD.main ended instead
	 */
	private static boolean waitForStartup(final Thread mudThread)
	{
		String lastStatus = "";
		while(mudThread.isAlive())
		{
			final List<MudHost> hosts = CMLib.hosts();
			boolean allAccepting = hosts.size() > 0;
			for(final MudHost host : hosts)
				allAccepting = allAccepting && host.isAcceptingConnections();
			if(allAccepting && !isShuttingDown())
			{
				if(lastStatus.length()>0)
					console.println();
				return true;
			}
			final CMProps props = mainProps();
			String status = (props == null) ? "" : props.getStr(CMProps.Str.MUDSTATUS);
			if((status != null) && status.startsWith("Booting") && (!status.equals(lastStatus)))
			{
				lastStatus = status;
				if(status.length() > 70)
					status = status.substring(0, 70);
				console.print("\r  "+String.format("%-72s", status));
				console.flush();
			}
			CMLib.s_sleep(250);
		}
		if(lastStatus.length()>0)
			console.println();
		return false;
	}

	/**
	 * Connects to the local game and pipes it to and from this console until
	 * the connection ends.
	 *
	 * @return false if the game could not be reached at all
	 */
	private static boolean playOnConsole()
	{
		final CMProps props = mainProps();
		final Charset outputCharset = charsetVar(props, CMProps.Str.CHARSETOUTPUT);
		final Charset inputCharset = charsetVar(props, CMProps.Str.CHARSETINPUT);
		final MudHost host = CMLib.hosts().get(0);
		final Socket sock;
		try
		{
			sock = new Socket(InetAddress.getLoopbackAddress(), host.getPort());
		}
		catch(final IOException e)
		{
			console.println("Unable to connect to the game: "+e.getMessage());
			return false;
		}
		currentSock = sock;
		try
		{
			// one keyboard reader for the whole run, so a restart can reconnect
			if(keyboardThread == null)
			{
				keyboardThread = new Thread(new Runnable()
				{
					@Override
					public void run()
					{
						pumpKeyboard(inputCharset);
					}
				}, "SoloKeyboard");
				keyboardThread.setDaemon(true);
				keyboardThread.start();
			}
			pumpGameOutput(sock.getInputStream(), sock.getOutputStream(), outputCharset);
		}
		catch(final IOException e)
		{
			// connection is gone, which ends the session
		}
		finally
		{
			currentSock = null;
			try
			{
				sock.close();
			}
			catch(final IOException e)
			{
			}
		}
		return true;
	}

	/**
	 * Returns the charset named by the given property, or iso-8859-1.
	 *
	 * @param props the main host props, or null
	 * @param var the charset property
	 * @return the charset
	 */
	private static Charset charsetVar(final CMProps props, final CMProps.Str var)
	{
		try
		{
			if(props != null)
				return Charset.forName(props.getStr(var));
		}
		catch(final Exception e)
		{
		}
		return Charset.forName("iso-8859-1");
	}

	/**
	 * Sends each line typed at the console to the current game connection.
	 * Closes the connection when console input ends (Ctrl+D / Ctrl+Z), which
	 * ends the session.
	 *
	 * @param charset the game input charset
	 */
	private static void pumpKeyboard(final Charset charset)
	{
		final BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
		try
		{
			String line;
			while((line = keyboard.readLine()) != null)
			{
				final Socket sock = currentSock;
				if(sock == null)
					continue;
				try
				{
					final OutputStream out = sock.getOutputStream();
					synchronized(out)
					{
						out.write((line+"\r\n").getBytes(charset));
						out.flush();
					}
				}
				catch(final IOException e)
				{
					// connection just ended; the output side will notice
				}
			}
		}
		catch(final IOException e)
		{
		}
		final Socket sock = currentSock;
		if(sock != null)
		{
			try
			{
				sock.close();
			}
			catch(final IOException e)
			{
			}
		}
	}

	/**
	 * Prints the game output to the console until the connection ends. Telnet
	 * option requests are all politely refused, telnet sub-negotiations are
	 * dropped, and MCP out-of-band lines are hidden, leaving plain text and
	 * ANSI color codes.
	 *
	 * @param in the game connection input
	 * @param out the game connection output, for telnet refusals
	 * @param charset the game output charset
	 * @throws IOException when the connection ends badly
	 */
	private static void pumpGameOutput(final InputStream in, final OutputStream out, final Charset charset) throws IOException
	{
		final byte[] buf = new byte[4096];
		final ByteArrayOutputStream text = new ByteArrayOutputStream();
		final StringBuilder lineStartHold = new StringBuilder("");
		boolean atLineStart = true;
		boolean skippingLine = false;
		int telnetState = 0; // 0=text, 1=after IAC, 2=option verb, 3=sub-negotiation, 4=IAC in sub-neg
		int verb = 0;
		int len;
		while((len = in.read(buf)) >= 0)
		{
			text.reset();
			for(int i=0;i<len;i++)
			{
				final int b = buf[i] & 0xff;
				switch(telnetState)
				{
				case 0:
					if(b == Session.TELNET_IAC)
						telnetState = 1;
					else
					if((b != '\r') && (b != 0))
						text.write(b);
					break;
				case 1:
					if(b == Session.TELNET_IAC)
					{
						text.write(b);
						telnetState = 0;
					}
					else
					if((b == Session.TELNET_WILL) || (b == Session.TELNET_WONT)
					|| (b == Session.TELNET_DO) || (b == Session.TELNET_DONT))
					{
						verb = b;
						telnetState = 2;
					}
					else
					if(b == Session.TELNET_SB)
						telnetState = 3;
					else
						telnetState = 0;
					break;
				case 2:
					// refuse everything; never answer a refusal, to avoid loops
					if(verb == Session.TELNET_WILL)
						sendTelnet(out, Session.TELNET_DONT, b);
					else
					if(verb == Session.TELNET_DO)
						sendTelnet(out, Session.TELNET_WONT, b);
					telnetState = 0;
					break;
				case 3:
					if(b == Session.TELNET_IAC)
						telnetState = 4;
					break;
				case 4:
					telnetState = (b == Session.TELNET_SE) ? 0 : 3;
					break;
				}
			}
			if(text.size() == 0)
				continue;
			final String str = new String(text.toByteArray(), charset);
			final StringBuilder show = new StringBuilder(str.length());
			for(int c=0;c<str.length();c++)
			{
				final char ch = str.charAt(c);
				if(skippingLine)
				{
					if(ch == '\n')
					{
						skippingLine = false;
						atLineStart = true;
					}
					continue;
				}
				if(atLineStart
				&& (lineStartHold.length() < MCP_PREFIX.length())
				&& (ch == MCP_PREFIX.charAt(lineStartHold.length())))
				{
					lineStartHold.append(ch);
					if(lineStartHold.length() == MCP_PREFIX.length())
					{
						lineStartHold.setLength(0);
						skippingLine = true;
					}
					continue;
				}
				if(lineStartHold.length() > 0)
				{
					show.append(lineStartHold);
					lineStartHold.setLength(0);
				}
				show.append(ch);
				atLineStart = (ch == '\n');
			}
			console.print(show.toString());
			console.flush();
		}
	}

	/**
	 * Sends a telnet option reply to the game.
	 *
	 * @param out the game connection output
	 * @param verb the reply verb
	 * @param option the telnet option
	 * @throws IOException when the connection ends badly
	 */
	private static void sendTelnet(final OutputStream out, final int verb, final int option) throws IOException
	{
		synchronized(out)
		{
			out.write(new byte[] { (byte)Session.TELNET_IAC, (byte)verb, (byte)option });
			out.flush();
		}
	}

	/**
	 * Saves the game and shuts it down, unless that's already happening.
	 * The shutdown runs in the main host's thread group so that it sees
	 * that host's libraries.
	 */
	private static void shutdownMud()
	{
		savingStarted = true;
		console.println();
		console.println("Saving the game and shutting down...");
		final ThreadGroup hostGroup = (CMLib.hosts().size() > 0)
			? ((Thread)CMLib.hosts().get(0)).getThreadGroup()
			: Thread.currentThread().getThreadGroup();
		final Thread shutdownThread = new Thread(hostGroup, new Runnable()
		{
			@Override
			public void run()
			{
				if(!CMProps.isState(CMProps.HostState.SHUTTINGDOWN))
					MUD.globalShutdown(null, true, null);
			}
		}, "SoloShutdown");
		shutdownThread.start();
		try
		{
			shutdownThread.join();
		}
		catch(final InterruptedException e)
		{
		}
	}
}
