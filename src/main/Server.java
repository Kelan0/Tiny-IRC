package main;

import javafx.util.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;

/**
 * @author Kelan
 */
public class Server
{
    private static int PORT = 8088;
    private static boolean running = true;

    private static final HashMap<String, Handler> users = new HashMap<>();
    private static List<Pair<String, String>> messageHistory = new ArrayList<>();

    public static void main(String[] args) throws IOException
    {
        UpdateHandler.startCommandThread();
        UpdateHandler.startTimeoutThread();

        System.out.println("The chat server is running.");
        ServerSocket listener = new ServerSocket(PORT);
        try
        {
            while (isRunning())
            {
                new Thread(new Handler(listener.accept())).start();
            }
        } finally
        {
            sendToAll("SERVER", "Server is shutting down", true);

            for (Handler handler : users.values())
                handler.out.println("SERVER_CLOSING");

            listener.close();
        }
    }

    public static boolean sendToAll(String from, String message, boolean log)
    {
        if (message == null || message.isEmpty())
            return false;

        if (log)
            System.out.println("\"" + from + "\" -> Everyone : \"" + message + "\"");

        messageHistory.add(new Pair<>(from, message));

        for (Handler handler : users.values())
            handler.out.println("MESSAGE[" + from + "]" + message);

        return true;
    }

    public static boolean sendTo(String from, String to, String message, boolean log)
    {
        Handler handler;

        if (message == null || message.isEmpty() || (handler = users.get(to)) == null)
            return false;

        if (log)
            System.out.println("\"" + from + "\" -> \"" + to + "\" : \"" + message + "\"");

        handler.out.println("MESSAGE[" + from + "]" + message);

        return true;
    }

    public static synchronized boolean isRunning()
    {
        return running;
    }

    public static synchronized void setRunning(boolean running)
    {
        Server.running = running;
    }

    private static class Handler implements Runnable
    {
        private Socket socket;
        private PrintWriter out;
        private String username;
        private boolean connected;
        private long timeConnected;
        private long lastPingReceived;
        private long lastPingSent;
        private long lastMessage;

        private String leaveMessage = null;

        public Handler(Socket socket)
        {
            this.socket = socket;
            this.connected = true;
            this.timeConnected = System.nanoTime();
            this.lastPingReceived = System.nanoTime();
            this.lastMessage = System.nanoTime();
        }

        @Override
        public void run()
        {
            System.out.println("Running connection from socket " + socket);
            try
            {
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                out = new PrintWriter(socket.getOutputStream(), true);

                while (isRunning() && isConnected())
                {
                    out.println("SUBMIT_NAME");
                    username = in.readLine();

                    if (username == null || username.equals("\0"))
                    {
                        System.out.println("Connection cancelled, disconnecting");
                        socket.close();
                        return;
                    }

                    System.out.println("Received username \"" + username + "\"");

                    if ((username = username.trim()).isEmpty() || username.length() > 32 || !username.matches("[a-zA-Z0-9]+"))
                    {
                        if (username.isEmpty())
                            out.println("NAME_DENIED No name specified");
                        if (username.length() > 32)
                            out.println("NAME_DENIED Name was longer than the maximum (32) character limit");
                        if (!username.matches("[a-zA-Z0-9]+"))
                            out.println("NAME_DENIED Name must contain only alphanumeric characters, and no spaces.");

                        System.out.println("Username denied, invalid name");
                        continue;
                    }

                    synchronized (users)
                    {
                        if (!users.containsKey(username))
                        {
                            users.put(username, this);
                            out.println("NAME_ACCEPTED " + username);
                            System.out.println("Username accepted");
                            break;
                        } else
                        {
                            out.println("NAME_DENIED Username is already in use");
                            System.out.println("Username denied, Already in use");
                            continue;
                        }
                    }
                }

                for (Pair<String, String> message : messageHistory)
                    sendTo(message.getKey(), username, message.getValue(), true);

                sendToAll("SERVER", username + " has joined the server!", true);

                while (isRunning() && isConnected())
                {
                    try
                    {
                        String line = in.readLine();

                        long now = System.nanoTime();

                        if (line.startsWith("PING"))
                        {
                            synchronized (this)
                            {
                                lastPingReceived = now;
                            }
                        } else if (line.startsWith("DISCONNECT"))
                        {
                            leaveMessage = "leaving";
                            break;
                        } else
                        {
                            sendToAll(username, line, true);
                            lastMessage = now;
                        }
                    } catch (SocketException e)
                    {
                    }
                }
            } catch (IOException e)
            {
                e.printStackTrace();
                leaveMessage = e.getMessage();
            } finally
            {
                users.remove(username);

                if (username != null && !username.equals("\0"))
                    sendToAll("SERVER", username + " has disconnected" + (leaveMessage != null && !(leaveMessage = leaveMessage.trim()).isEmpty() ? " - " + leaveMessage : ""), true);

                try
                {
                    if (socket != null && !socket.isClosed())
                        socket.close();
                } catch (IOException e)
                {
                    e.printStackTrace();
                }

                socket = null;
                disconnect(null);
            }
        }

        public String getFormattedConnectionTime()
        {
            long seconds = (System.nanoTime() - timeConnected) / 1000000000;
            long s = seconds % 60;
            long m = (seconds / 60) % 60;
            long h = (seconds / (60 * 60)) % 24;
            long d = (seconds / (60 * 60 * 24));
            return d <= 0 ? String.format("%02dh %02dm %02ds", h, m, s) : String.format("%dd %02dh %02dm %02ds", d, h, m, s);
        }

        public void kick()
        {
            out.println("KICKED kicked by an admin");
            disconnect("kicked by an admin");
            try
            {
                socket.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
            System.out.println(isConnected());
        }

        public synchronized void checkTimeout()
        {
            long now = System.nanoTime();

            if (now - lastPingSent > 1000000000L) // 1 second
            {
                out.println("PING");
                lastPingSent = System.nanoTime();
            }

            if (now - lastPingReceived > 30000000000L) // 30 seconds
            {
                out.println("KICKED connection timed out");
                disconnect("connection timed out");
            }

            if (now - lastMessage > 600000000000L) // 600 seconds
            {
                out.println("KICKED kicked due to inactivity");
                disconnect("kicked due to inactivity");
            }
        }

        public synchronized boolean isConnected()
        {
            return connected;
        }

        public synchronized void disconnect(String reason)
        {
            this.connected = false;
            this.leaveMessage = reason;
        }

        public synchronized void purge(int count)
        {
            out.println("PURGE " + count);
        }
    }

    private static abstract class UpdateHandler
    {
        private static Set<UpdateHandler> allCommands = new HashSet<>();

        private static final UpdateHandler COMMAND_SHUTDOWN = new UpdateHandler("shutdown", "Shuts the server down and closes all user connections.")
        {
            @Override
            public void execute(String line)
            {
                running = false;
            }
        };

        private static final UpdateHandler COMMAND_KICK = new UpdateHandler("kick", "Kicks a specified user from the server. They will be able to reconnect afterwards.")
        {
            @Override
            public void execute(String line)
            {
                line = line.trim();

                if (line.length() > name.length())
                {
                    String username = line.substring(name.length() + 1);
                    Handler handler = users.get(username);

                    if (handler != null)
                    {
                        handler.kick();
                    } else
                    {
                        System.err.println("Unknown user \"" + username + "\"");
                    }
                } else
                {
                    System.err.println("Unspecified user.");
                }
            }
        };

        private static final UpdateHandler COMMAND_LIST = new UpdateHandler("list", "Lists all users connected to the server. Details listed include the users name, their connection time, and their IP address.")
        {
            @Override
            public void execute(String line)
            {
                System.out.println("Currently connected users:");
                for (String user : users.keySet())
                {
                    Handler handler = users.get(user);
                    System.out.println("\t\"" + user + "\" | " + handler.getFormattedConnectionTime() + " | " + handler.socket.getRemoteSocketAddress());
                }
            }
        };

        private static final UpdateHandler COMMAND_PURGE = new UpdateHandler("purge", "Purges all previous message history")
        {
            @Override
            public void execute(String line)
            {
                for (Handler handler : users.values())
                    handler.purge(0);

                messageHistory.clear();

                sendToAll("SERVER", "Message history purged", true);
            }
        };

        public String name;
        public String description;

        public UpdateHandler(String name, String description)
        {
            this.name = name;
            this.description = description;
            allCommands.add(this);
        }

        private static void startCommandThread()
        {
            new Thread(() -> {
                Scanner scanner = new Scanner(System.in);

                while (isRunning())
                {
                    String command = scanner.nextLine();

                    if (command != null && !(command = command.trim()).isEmpty())
                    {
                        if (command.equals("shutdown"))
                            COMMAND_SHUTDOWN.execute(command);
                        else if (command.startsWith("kick"))
                        {
                            COMMAND_KICK.execute(command);
                        } else if (command.equals("list"))
                        {
                            COMMAND_LIST.execute(command);
                        } else if (command.equals("help"))
                        {
                            for (UpdateHandler c : allCommands)
                                System.out.println(c.name + ":\t" + c.description + "\n");
                        } else if (command.equals("purge"))
                        {
                            COMMAND_PURGE.execute(command);
                        } else
                        {
                            System.out.println("Unknown command \"" + command + "\"");
                        }
                    }
                }
            }).start();
        }

        private static void startTimeoutThread()
        {
            new Thread(() -> {
                while (isRunning())
                {
                    for (Handler handler : users.values())
                        handler.checkTimeout();

                    try
                    {
                        Thread.sleep(500);
                    } catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                }
            }).start();
        }

        public abstract void execute(String line);
    }
}
