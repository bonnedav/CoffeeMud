package com.planet_ink.coffee_mud.application;
import java.io.*;
import java.net.*;
import java.util.*;
import java.sql.*;
import com.planet_ink.coffee_mud.system.*;
import com.planet_ink.coffee_mud.utils.*;
import com.planet_ink.coffee_mud.interfaces.*;
import com.planet_ink.coffee_mud.common.*;
import com.planet_ink.coffee_mud.Commands.base.CommandProcessor;
import com.planet_ink.coffee_mud.Commands.base.ExternalCommands;
import com.planet_ink.coffee_mud.web.*;


public class MUD extends Thread implements Host
{
	public SaveThread saveThread=null;
	public INI page=null;
	public boolean keepDown=true;
	public String execExternalCommand=null;

	public static final float HOST_VERSION_MAJOR=(float)3.0;
	public static final float HOST_VERSION_MINOR=(float)0.6;
	
	private boolean acceptConnections=false;
	private String offlineReason=new String("UNKNOWN");
	public boolean isOK = false;
	
	public ServerSocket servsock=null;

	public INI webCommon=null;
	public HTTPserver webServerThread=null;
	public HTTPserver adminServerThread=null;
	public final static String ServerVersionString = "CoffeeMUD-MainServer/" + HOST_VERSION_MAJOR + "." + HOST_VERSION_MINOR;
	public boolean serverIsRunning = false;

	public MUD()
	{
		super("MUD-MainServer");

		isOK = false;
		
		if (!loadPropPage())
		{
			Log.errOut("MUD","ERROR: Unable to read ini file.");
			offlineReason=new String("A terminal error has occured!");
		}
		else
		{
			isOK = true;
			offlineReason=new String("Booting");
		}
		acceptConnections = false;
	}	
	
	private boolean loadPropPage()
	{
		if (page==null || !page.loaded)
		{
			page=new INI("coffeemud.ini");
			if(!page.loaded)
				return false;
		}
		return true;
	}

	private boolean loadWebCommonPropPage()
	{
		if (webCommon==null || !webCommon.loaded)
		{
			webCommon=new INI("web" + File.separatorChar + "common.ini");
			if(!webCommon.loaded)
				return false;
		}
		return true;
	}

	public Properties getCommonPropPage()
	{
		return webCommon;
	}

	public void fatalStartupError(int type)
	{
		String str=null;
		switch(type)
		{
		case 1:
			str="ERROR: initHost() will not run without properties. Exiting.";
			break;
		case 2:
			str="Map is empty?! Exiting.";
			break;
		case 3:
			str="Database init failed. Exiting.";
			break;
		case 4:
			str="Fatal exception. Exiting.";
			break;
		case 5:
			str="MUD Server did not start. Exiting.";
			break;
		default:
			str="Fatal error loading classes.  Make sure you start up coffeemud from the directory containing the class files.";
			break;
		}
		Log.errOut("MUD",str);
		this.interrupt();
	}
	

	private boolean initHost()
	{

		if (!isOK)
		{
			this.interrupt();
			return false;
		}

		if ((page == null) || (!page.loaded))
		{
			fatalStartupError(1);
			return false;
		}

		Log.Initialize(page.getStr("SYSMSGS"),page.getStr("ERRMSGS"),page.getStr("DBGMSGS"));
		System.out.println();
		Log.sysOut("MUD",getVer());
		Log.sysOut("MUD","(C) 2000-2002 Bo Zimmerman");
		Log.sysOut("MUD","www.zimmers.net/home/mud.html");
		Log.sysOut("MUD","Starting...");
		
		while (!serverIsRunning && isOK)
		{
		}
		if (!isOK)
		{
			fatalStartupError(5);
			return false;
		}
		
		if (page.getStr("RUNWEBSERVERS").equalsIgnoreCase("true"))
		{
			if (loadWebCommonPropPage())
			{
				if(HTTPserver.loadWebMacros())
				{
					Log.sysOut("MUD","Attempting to start web servers");
					webServerThread = new HTTPserver(this,"pub");
					webServerThread.start();
					adminServerThread = new HTTPserver(this,"admin");
					adminServerThread.start();
				}
				else
				{
					Log.errOut("MUD","Unable to start web server - loadWebMacros() failed");
				}
			}
			else
				Log.errOut("MUD","Unable to start web server - loadWebCommonPropPage() failed");
		}


		
		DBConnector.DBConfirmDeletions=page.getBoolean("DBCONFIRMDELETIONS");
		offlineReason=new String("Booting: connecting to database");
		DBConnector.connect(page.getStr("DBCLASS"),page.getStr("DBSERVICE"),page.getStr("DBUSER"),page.getStr("DBPASS"),page.getInt("DBCONNECTIONS"),true);
		String DBerrors=DBConnector.errorStatus().toString();
		if(DBerrors.length()==0)
			Log.sysOut("MUD","Database connection successful.");
		else
		{
			Log.errOut("MUD","Fatal database error: "+DBerrors);
			System.exit(-1);
			//fatalStartupError(3);
			//return false;
		}
		if(DBConnector.DBConfirmDeletions)
			Log.sysOut("MUD","DB Deletions will be confirmed.");
		
		CommandProcessor commandProcessor=new CommandProcessor();
		ExternalPlay.setPlayer(new ExternalCommands(commandProcessor), new ExternalSystems());

		if(!CMClass.loadClasses(page))
		{
			fatalStartupError(0);
			return false;
		}

		int numChannelsLoaded=commandProcessor.channels.loadChannels(page.getStr("CHANNELS"),commandProcessor.commandSet);
		commandProcessor.myHost=this;
		Log.sysOut("MUD","Channels loaded   : "+numChannelsLoaded);

		commandProcessor.socials.load("resources"+File.separatorChar+"socials.txt");
		if(!commandProcessor.socials.loaded)
			Log.errOut("MUD","WARNING: Unable to load socials from socials.txt!");
		else
			Log.sysOut("MUD","Socials loaded    : "+commandProcessor.socials.num());

		Log.sysOut("MUD","Loading map...");
		offlineReason=new String("Booting: loading rooms (this can take a while).");
		RoomLoader.DBRead(CMMap.getAreaVector(),CMMap.getRoomVector());
		Log.sysOut("MUD","Mapped rooms      : "+CMMap.numRooms()+" in "+CMMap.numAreas()+" areas");
		if(CMMap.numRooms()==0)
		{
			Log.sysOut("NO MAPPED ROOM?!  I'll make ya one!");
			String id=page.getStr("START");
			if(id.length()==0) id="START";
			Area newArea=ExternalPlay.DBCreateArea("New Area","StdArea");
			Room room=CMClass.getLocale("StdRoom");
			room.setArea(newArea);
			room.setID(id);
			room.setDisplayText("New Room");
			room.setDescription("Brand new database room! You need to change this text with the MODIFY ROOM command.  If your character is not an Archon, pick up the book you see here and read it immediately!");
			RoomLoader.DBCreate(room,"StdRoom");
			Item I=CMClass.getMiscMagic("ManualArchon");
			room.addItem(I);
			CMMap.addRoom(room);
		}

		offlineReason=new String("Booting: readying for connections.");
		try
		{
			CMMap.setStartRoom(page.getStr("START"));

			commandProcessor.commandSet.loadAbilities(CMClass.abilities);

			saveThread=new SaveThread();
			saveThread.start();
			Log.sysOut("MUD","Save thread started");
		}
		catch (Throwable t)
		{
			Log.sysOut("MUD","CoffeeMud Server initHost() failed");
			fatalStartupError(4);
			return false;
		}

		acceptConnections = true;
		Log.sysOut("MUD","Initialization complete.");
		offlineReason=new String("UNKNOWN");
		return true;
	}
	
	
	public void run()
	{
		int q_len = 6;
		Socket sock=null;
		serverIsRunning = false;


		if (!isOK)	return;
		if ((page == null) || (!page.loaded))
		{
			Log.errOut("MUD","ERROR: Host thread will not run with no properties.");
			return;
		}
		
		InetAddress bindAddr = null;

		if (page.getInt("BACKLOG") > 0)
			q_len = page.getInt("BACKLOG");

		if (page.getStr("BIND") != null && page.getStr("BIND").length() > 0)
		{
			try
			{
				bindAddr = InetAddress.getByName(page.getStr("BIND"));
			}
			catch (UnknownHostException e)
			{
				Log.errOut("MUD","ERROR: MUD Server could not bind to address " + page.getStr("BIND"));
				bindAddr = null;
			}
		}
		
		try
		{
			servsock=new ServerSocket(page.getInt("PORT"), q_len, bindAddr);

			Log.sysOut("MUD","MUD Server started on port: "+page.getInt("PORT"));
			if (bindAddr != null)
				Log.sysOut("MUD","MUD Server bound to: "+bindAddr.toString());
			serverIsRunning = true;

			while(true)
			{
				sock=servsock.accept();
				
				if (acceptConnections)
				{
					Log.sysOut("MUD","Got a connection.");
					StringBuffer introText=Resources.getFileResource("intro.txt");
					TelnetSession S=new TelnetSession(sock,
						introText != null ? introText.toString() : null);
					S.start();
					Sessions.addElement(S);
					sock = null;
				}
				else
				{
					Log.sysOut("MUD","Rejecting a connection.");
					StringBuffer rejectText=Resources.getFileResource("offline.txt");
					PrintWriter out = new PrintWriter(sock.getOutputStream());
					out.println("\n\rOFFLINE: " + offlineReason+"\n\r");
					out.flush();
					out.println(rejectText);
					out.flush();
					out.close();
					sock = null;
				}
			}
		}
		catch(Throwable t)
		{
			if((t!=null)&&(t instanceof Exception))
				Log.errOut("MUD",((Exception)t).getMessage());
	
			if (!serverIsRunning)
				isOK = false;
		}
		
		Log.sysOut("MUD","CoffeeMud Server cleaning up.");

		try
		{
			if(servsock!=null)
				servsock.close();
			if(sock!=null)
				sock.close();
		}
		catch(IOException e)
		{
		}

		Log.sysOut("MUD","CoffeeMud Server thread stopped!");
	}

	
	public void interrupt()
	{
		if(servsock!=null)
		{
			try
			{
				servsock.close();
				servsock = null;
			}
			catch(IOException e)
			{
			}
		}
		super.interrupt();
	}


	public void shutdown(Session S, boolean keepItDown, String externalCommand)
	{
		if(saveThread==null) return;

		offlineReason=new String("Shutting down" + (keepItDown? "..." : " and restarting...") );
		acceptConnections = false;
		Log.sysOut("MUD","Host will now reject new connections.");
		S.println("Host will now reject new connections.");

		saveThread.shutdown();
		saveThread.interrupt();
		saveThread=null;
		S.println("Save thread stopped.");

		for(int s=0;s<Sessions.size();s++)
		{
			Session session=Sessions.elementAt(s);
			if(session.mob()!=null)
			{
				offlineReason=new String("Shutting down...Saving "+session.mob().name());
				MOBloader.DBUpdate(session.mob());
				offlineReason=new String("Shutting down...Saving followers of "+session.mob().name());
				MOBloader.DBUpdateFollowers(session.mob());
				offlineReason=new String("Shutting down...Done saving "+session.mob().name());
				offlineReason="Done saving mob "+session.mob().name();
			}
		}
		Log.sysOut("MUD","All users saved.");
		S.println("All users saved.");
		offlineReason=new String("Shutting down...Users saved");

		while(Sessions.size()>0)
		{
			Session S2=Sessions.elementAt(0);
			if(S2==S)
				Sessions.removeElementAt(0);
			else
			{
				offlineReason=new String("Shutting down...Stopping session "+S2.getTermID());
				S2.logoff();
				offlineReason=new String("Shutting down...Done stopping session "+S2.getTermID());
			}
		}
		S.println("All users logged off.");

		offlineReason=new String("Shutting down...shutting down service engine");
		ServiceEngine.shutdownAll();
		S.println("All threads stopped.");

		offlineReason=new String("Shutting down...closing db connections");
		DBConnector.killConnections();
		Log.sysOut("MUD","All users saved.");
		S.println("Database connections closed.");

		offlineReason=new String("Shutting down...unloading classes");
		CMClass.unload();
		offlineReason=new String("Shutting down...unloading map");
		CMMap.unLoad();
		page=null;
		offlineReason=new String("Shutting down...unloading resources");
		Resources.clearResources();
		webCommon=null;
		if(webServerThread!=null)
		{
			offlineReason=new String("Shutting down...pub webserver");
			webServerThread.shutdown(S);
			webServerThread = null;
		}
		if(adminServerThread!=null)
		{
			offlineReason=new String("Shutting down...admin webserver");
			adminServerThread.shutdown(S);
			adminServerThread = null;
		}
		offlineReason=new String("Shutting down...unloading macros");
		HTTPserver.unloadWebMacros();
		offlineReason=new String("Shutting down" + (keepItDown? "..." : " and restarting...") );

		try{Thread.sleep(500);}catch(Exception i){}
		Log.sysOut("MUD","CoffeeMud shutdown complete.");
		S.println("CoffeeMud shutdown complete.");
		if(!keepItDown)
			S.println("Restarting...");
		S.logoff();
		try{Thread.sleep(500);}catch(Exception i){}
		System.gc();
		System.runFinalization();
		try{Thread.sleep(500);}catch(Exception i){}

		this.keepDown=keepItDown;
		this.execExternalCommand=externalCommand;
		offlineReason=new String("Shutdown: you are the special lucky chosen one!");
		this.interrupt();
	}


	public static void threadList(ThreadGroup tGroup)
	{
		int ac = tGroup.activeCount();
		Thread tArray[] = new Thread [ac+1];
		tGroup.enumerate(tArray);
		for (int i = 0; i<ac; ++i)
		{
			if (tArray[i] != null && tArray[i].isAlive())
				Log.sysOut("MUD", "-->Thread: "+tArray[i].getName() + "\n\r");
		}
	}
	
	public String getVer()
	{
		return "CoffeeMud v"+HOST_VERSION_MAJOR+"."+HOST_VERSION_MINOR;
	}

	public boolean isGameRunning()
	{
		return acceptConnections;
	}
	
	public int getPort()
	{
		return page.getInt("PORT");
	}
	public String getPortStr()
	{
		return page.getStr("PORT");
	}
	
	public String ServerVersionString()
	{
		return ServerVersionString;
	}
	
	public String gameStatusStr()
	{
		if (acceptConnections)
			return "OK";
		else
			return offlineReason;
	}












	
	public static void main(String a[]) throws IOException
	{
		Log.startLogFiles();
		
		try
		{
			while(true)
			{
				MUD mud=new MUD();
				mud.start();
				mud.initHost();
				mud.join();
				System.gc();
				System.runFinalization();
				boolean keepDown=mud.keepDown;
				String external=mud.execExternalCommand;
				mud=null;
				System.gc();
				System.runFinalization();
				if(Thread.activeCount()>1)
				{
					Log.sysOut("MUD","WARNING: " + (Thread.activeCount()-1) +" other thread(s) are still active!");
					threadList(Thread.currentThread().getThreadGroup());
				}
				if(keepDown)
					break;
				if(external!=null)
				{
					//Runtime r=Runtime.getRuntime();
					//Process p=r.exec(external);
					Log.sysOut("Attempted to execute '"+external+"'.");
					break;
				}
			}
		}
		catch(InterruptedException e)
		{
			Log.errOut("MUD",e);
		}
	}


}
