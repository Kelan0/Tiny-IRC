package main;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;

/**
 * @author Kelan
 */
public class Client
{
    private final JFrame frame;
    private JPanel contentPanel;
    private JPanel messagePanel;
    private JPanel inputPanel;
    private JPanel connectionPanel;
    private JScrollPane scrollMessagePanel;
    private JScrollPane scrollInputField;
    private JTextArea inputField;
    private JButton sendButton;
    private JButton connectButton;
    private JLabel connectionDetails;

    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private Socket socket;
    private int lastMessageHeight;

    public Client()
    {
        try
        {
            String lookAndFeel = UIManager.getSystemLookAndFeelClassName();
            UIManager.setLookAndFeel(lookAndFeel);
        } catch (ClassNotFoundException | InstantiationException | UnsupportedLookAndFeelException | IllegalAccessException e)
        {
            e.printStackTrace();
        }

        frame = new JFrame("Chat Client");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(createContentPane());
        frame.setSize(600, 600);
        frame.setResizable(true);
        frame.setVisible(true);

        frame.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent e)
            {
                if (socket != null && !socket.isClosed())
                    out.println("DISCONNECT");

                socket = null;
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> out.println("DISCONNECT")));
    }

    private void createConnection()
    {
        new Thread(() -> {
            if (socket != null && !socket.isClosed())
                out.println("DISCONNECT");

            socket = getConnection();

            if (socket == null)
                return;

            try
            {
                socket.setSoTimeout(30000);
            } catch (SocketException e)
            {
                e.printStackTrace();
            }

            try
            {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                out = new PrintWriter(socket.getOutputStream(), true);
            } catch (IOException e)
            {
                JOptionPane.showMessageDialog(frame, "Failed to create IO buffers\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                e.printStackTrace();
            }

            while (true)
            {
                if (socket == null || socket.isClosed())
                    break;

                try
                {
                    String line = in.readLine();

                    if (line == null || line.isEmpty())
                        continue;

                    if (line.startsWith("PING"))
                    {
                        System.out.println("Pinged by server");
                        out.println("PING"); //ping the server back.
                    }

                    if (line.startsWith("SUBMIT_NAME"))
                    {
                        String username = getUsername();
                        out.println(username);
                        if (username == null || username.equals("\0"))
                            socket = null;
                    }

                    if (line.startsWith("NAME_ACCEPTED"))
                    {
                        username = line.substring("NAME_ACCEPTED".length() + 1);
                        scrollMessagePanel.getVerticalScrollBar().setValue(scrollMessagePanel.getVerticalScrollBar().getMaximum());
                    }

                    if (line.startsWith("NAME_DENIED"))
                    {
                        String reason = line.substring("NAME_DENIED".length() + 1).trim();
                        JOptionPane.showMessageDialog(frame, "Invalid username" + (!reason.isEmpty() ? "\n" + reason : ""), "Error", JOptionPane.ERROR_MESSAGE);
                    }

                    if (line.startsWith("MESSAGE"))
                    {
                        receiveMessage(line);
                    }

                    if (line.startsWith("SERVER_CLOSING"))
                    {
                        socket = null;
                    }

                    if (line.startsWith("KICKED"))
                    {
                        String message = null;

                        if (line.trim().length() > "KICKED".length())
                            message = line.substring("KICKED".length() + 1);

                        socket = null;
                        JOptionPane.showMessageDialog(frame, "You have been kicked" + (message != null ? "\n" + message : ""), "Kicked", JOptionPane.INFORMATION_MESSAGE);
                    }

                    if (line.startsWith("PURGE"))
                    {
                        int amount = -1;

                        try
                        {
                            amount = Integer.parseInt(line.substring("PURGE".length() + 1).trim());
                        } catch (Exception e)
                        {
                        }

                        messagePanel.removeAll();
                    }

                    Thread.sleep(8);

                    connectionDetails.setText(socket != null ? "Connected to " + socket.getRemoteSocketAddress() + " as " + username : "Disconnected");
                } catch (IOException | InterruptedException e)
                {
                    e.printStackTrace();
                }
            }

            if (socket != null && !socket.isClosed())
            {
                try
                {
                    socket.close();
                    in.close();
                    out.close();
                    socket = null;
                    in = null;
                    out = null;
                } catch (IOException e)
                {
                    e.printStackTrace();
                }
            }

            messagePanel.removeAll();
            messagePanel.revalidate();
            messagePanel.repaint();

        }).start();
    }

    private Socket getConnection()
    {
        String address = JOptionPane.showInputDialog(frame, "Enter IP Address of the Server:", "Connect", JOptionPane.QUESTION_MESSAGE);

        if (address == null)
            return null;

        if (!Utils.validateIPv4(address, true))
        {
            JOptionPane.showMessageDialog(frame, "Invalid IP address", "Error", JOptionPane.ERROR_MESSAGE);
            return getConnection();
        }

        String[] comps = address.split(":");

        if (comps.length != 2)
        {
            JOptionPane.showMessageDialog(frame, "Invalid IP address", "Error", JOptionPane.ERROR_MESSAGE);
            return getConnection();
        }

        String host = comps[0].trim();
        int port = Integer.parseInt(comps[1].trim());

        try
        {
            return new Socket(host, port);
        } catch (IOException e)
        {
            JOptionPane.showMessageDialog(frame, "Failed to connect to remote host\nConnection refused: connect", "Error", JOptionPane.ERROR_MESSAGE);
            return getConnection();
        }
    }

    private String getUsername()
    {
        String username = JOptionPane.showInputDialog(frame, "Enter unique username:", "User", JOptionPane.PLAIN_MESSAGE);

        if (username == null)
            return "\0";

        if ((username = username.trim()).isEmpty())
        {
            JOptionPane.showMessageDialog(frame, "Invalid Username\nName cannot be blank", "Error", JOptionPane.ERROR_MESSAGE);
            return getUsername();
        }

        return username;
    }

    private JPanel createContentPane()
    {
        Action sendMessageAction = new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                sendMessage();
            }
        };

        Action newLineAction = new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                inputField.setText(inputField.getText() + "\n");
            }
        };

        Action connectAction = new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                if (socket == null || socket.isClosed())
                    createConnection();
                else
                {
                    out.println("DISCONNECT");
                    socket = null;
                }
            }
        };

        int inputPanelHeight = 90;
        int connectionPanelHeight = 30;
        int buttonWidth = 90;

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.PAGE_AXIS));

        messagePanel = new JPanel()
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(getParent().getWidth(), super.getMaximumSize().height);
            }
        };
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.PAGE_AXIS));
        scrollMessagePanel = new JScrollPane(messagePanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(getParent().getWidth(), getParent().getHeight() - (inputPanelHeight + connectionPanelHeight));
            }
        };
        inputPanel = new JPanel(new BorderLayout(2, 2))
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(getParent().getWidth(), inputPanelHeight);
            }
        };
        inputField = new JTextArea();
        inputField.setLineWrap(true);
        inputField.setWrapStyleWord(true);
        inputField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        inputField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline");
        inputField.getActionMap().put("send", sendMessageAction);
        inputField.getActionMap().put("newline", newLineAction);
        scrollInputField = new JScrollPane(inputField, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(getParent().getWidth() - buttonWidth, inputPanelHeight);
            }
        };
        sendButton = new JButton("Send");
        sendButton.setPreferredSize(new Dimension(buttonWidth, inputPanelHeight));
        sendButton.addActionListener(sendMessageAction);
        inputPanel.add(scrollInputField, BorderLayout.LINE_START);
        inputPanel.add(sendButton, BorderLayout.LINE_END);

        connectionPanel = new JPanel(new BorderLayout(2, 2))
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(getParent().getWidth(), connectionPanelHeight);
            }
        };
        connectionDetails = new JLabel("Disconnected")
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(getParent().getWidth() - buttonWidth, connectionPanelHeight);
            }
        };
        connectButton = new JButton(connectAction)
        {
            @Override
            public String getText()
            {
                return socket == null || socket.isClosed() ? "Connect" : "Disconnect";
            }
        };
        connectButton.setPreferredSize(new Dimension(buttonWidth, connectionPanelHeight));
        connectionPanel.add(connectionDetails, BorderLayout.LINE_START);
        connectionPanel.add(connectButton, BorderLayout.LINE_END);

        JPanel bottomPanel = new JPanel()
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(getParent().getWidth(), (inputPanelHeight + connectionPanelHeight));
            }
        };

        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.PAGE_AXIS));
        bottomPanel.add(inputPanel);
        bottomPanel.add(connectionPanel);

        contentPanel.add(scrollMessagePanel);
        contentPanel.add(bottomPanel);
        return contentPanel;
    }

    private StyledDocument createStyledMessage(String text)
    {
        try
        {
            DefaultStyledDocument document = new DefaultStyledDocument();
            text = text.trim();
            document.insertString(0, text, null);
            return document;
        } catch (BadLocationException e)
        {
            e.printStackTrace();
        }

        return null;
    }

    private void createMessagePanel(JPanel messageArea, StyledDocument document, String username)
    {
        if (document != null && document.getLength() > 0)
        {
            try
            {
                JTextPane tempPane = new JTextPane(document);
                tempPane.setPreferredSize(new Dimension(1, 1));
                tempPane.setSize(new Dimension(1, 1));
                Rectangle bounds = tempPane.modelToView(document.getLength());

                final JTextPane textPane = new JTextPane(document)
                {
                    @Override
                    public Dimension getMinimumSize()
                    {
                        return new Dimension(getParent().getWidth(), bounds.height + bounds.y);
                    }

                    @Override
                    public Dimension getMaximumSize()
                    {
                        return new Dimension(getParent().getWidth(), bounds.height + bounds.y);
                    }

                    @Override
                    public boolean getScrollableTracksViewportWidth()
                    {
                        return true;
                    }
                };

                JPanel panel = new JPanel()
                {
                    @Override
                    public Dimension getMinimumSize()
                    {
                        return new Dimension(getParent().getWidth(), textPane.getPreferredScrollableViewportSize().height + getInsets().top + getInsets().bottom);
                    }

                    @Override
                    public Dimension getMaximumSize()
                    {
                        return new Dimension(getParent().getWidth(), textPane.getPreferredScrollableViewportSize().height + getInsets().top + getInsets().bottom);
                    }

                    @Override
                    public Dimension getSize()
                    {
                        return new Dimension(getParent().getWidth(), textPane.getPreferredScrollableViewportSize().height + getInsets().top + getInsets().bottom);
                    }
                };

                textPane.setEditable(false);
                textPane.setOpaque(false);

                panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
                panel.add(textPane);
                panel.setBorder(BorderFactory.createTitledBorder(username));

                messageArea.add(panel, new GridBagConstraints(0, 0, 1, 0, 1, 0, GridBagConstraints.NORTH, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 0), 0, 0));

                messageArea.revalidate();
                messageArea.repaint();

                lastMessageHeight = panel.getHeight();
            } catch (BadLocationException e)
            {
                e.printStackTrace();
            }
        }
    }

    public void sendMessage(String message)
    {
        inputField.setEditable(true);
        inputField.setText(message);
        sendMessage();
    }

    public void sendMessage()
    {
        if (out != null && socket != null && !socket.isClosed())
            out.println(inputField.getText().replace("\n", "\\n"));
        else
            JOptionPane.showMessageDialog(frame, "Cannot send message\nNot connected to a server", "Error", JOptionPane.ERROR_MESSAGE);

        inputField.setText("");
    }

    public void receiveMessage(String line)
    {
        if (line.startsWith("MESSAGE"))
        {
            System.out.println(System.getProperty("line.separator"));
            line = line.substring("MESSAGE".length() + 1).replace("\\n", "\n");

            String[] comps = line.split("]");
            receiveMessage(comps[0], comps[1]);
        }
    }

    public void receiveMessage(String username, String message)
    {
        JScrollBar verticalScrollBar = scrollMessagePanel.getVerticalScrollBar();

        boolean flag = verticalScrollBar.getValue() + verticalScrollBar.getModel().getExtent() >= verticalScrollBar.getMaximum() - 45;
        createMessagePanel(messagePanel, createStyledMessage(message), username);

        SwingUtilities.invokeLater(() -> {
            if (flag)
                verticalScrollBar.setValue(verticalScrollBar.getMaximum());
        });
    }

    public static void main(String[] args)
    {
        Client client = new Client();

//        client.sendMessage("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.");
    }
}
