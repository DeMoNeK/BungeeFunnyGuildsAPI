package demonek.BungeeFunnyGuildsAPI;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API FunnyGuilds pod BungeeCord'a
 * Autor: DeMoNeK_ 
 * Napisane dla serwera WESTMC.NET
 * 
 * Synchronizacja danych poprzez Proxy BungeeCord'a
 * Zapis logów gracza, gildii, sojuszy, chatu.
 * 
 * Data utworzenia plug-inu: 2019.05.24
 * Ostatnia data modyfikacji: 2019.05.24 | 12.11
 */
public class BungeeFunnyGuildsAPI extends Plugin implements Listener {

    Map<String, QueueingPluginMessage> pmQueue = new HashMap<>();

    @Override
    public void onEnable() {
        getProxy().registerChannel("funnyfunnyguildssync");
        getProxy().registerChannel("funnyfunnyguildsplayer");
        getProxy().registerChannel("funnyfunnyguildsbroadcast");
        getProxy().registerChannel("funnyfunnyguildserror");
        getProxy().getPluginManager().registerListener(this, this);
    }

    @EventHandler
    public void onPluginMessageReceived(PluginMessageEvent e) throws IOException {
        if (!e.getTag().startsWith("funnyfunnyguilds")) {
            return;
        }
        if (!(e.getSender() instanceof Server)) {
            return;
        }
        ByteArrayInputStream stream = new ByteArrayInputStream(e.getData());
        DataInputStream in = new DataInputStream(stream);
        String operation = e.getTag().split(":")[1];
        String[] args = in.readUTF().split(" ");
        String guild;
        UUID uuid;
        String message;

        switch (operation) {
            case "broadcast":
                String[] uuids = in.readUTF().split(Pattern.quote(","));
                message = in.readUTF();
                for (String uuidString : uuids) {
                    uuid = UUID.fromString(uuidString);
                    sendSingleMessage(uuid, message);
                }
                log(message);
                break;
            case "player":
                uuid = UUID.fromString(in.readUTF());
                if (args[0].equals("message")) {
                    message = in.readUTF();
                    sendSingleMessage(uuid, message);
                } else if (args[0].equals("guildhome")) {
                    guild = in.readUTF();
                    String homeServer = in.readUTF();
                    sendPlayerToGuildHome(uuid, guild, homeServer, (Server) e.getSender());
                }
                break;
            case "sync":
                switch (args[0]) {
                    case "funnyguilds":
                        sendQueueingMessage("sync", "funnyguilds",
                                getProxy().getPlayers().stream().map(p -> p.getUniqueId().toString()).collect(Collectors.joining(" ")));
                        break;
                    case "guild":
                        sendQueueingMessage("sync", "guild",
                                in.readInt(),
                                getProxy().getPlayers().stream().map(p -> p.getUniqueId().toString()).collect(Collectors.joining(" ")));
                        break;
                    case "alliances":
                        sendQueueingMessage("sync", "alliances");
                        break;
                    case "alliance":
                        sendQueueingMessage("sync", "alliance", in.readInt());
                        break;
                }
                break;
        }
    }

    private void sendQueueingMessage(String operation, String args, Object... write) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(args);
        for (Object w : write) {
            if (w instanceof String) {
                out.writeUTF((String) w);
            } else if (w instanceof Integer) {
                out.writeInt((Integer) w);
            } else {
                try (ByteArrayOutputStream bos = new ByteArrayOutputStream ();
                     ObjectOutputStream oOut = new ObjectOutputStream(bos)){
                    oOut.writeObject(w);
                    out.write(bos.toByteArray());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        QueueingPluginMessage pm = new QueueingPluginMessage(operation, args, out);
        for (ServerInfo serverInfo : getProxy().getServers().values()) {
            if (serverInfo.getPlayers().size() > 0) {
                pm.send(serverInfo);
            }
        }
        if (pm.getSendTo().size() < getProxy().getServers().size()) {
            pmQueue.put(pm.getCommand(), pm);
        } else {
            pmQueue.remove(pm.getCommand());
        }
    }

    @EventHandler
    public void onPlayerServerSwitch(ServerSwitchEvent event) {
        Iterator<QueueingPluginMessage> it = pmQueue.values().iterator();
        while (it.hasNext()) {
            QueueingPluginMessage pm = it.next();
            if(pm != null) {
                pm.send(event.getPlayer().getServer().getInfo());

                if (pm.getSendTo().size() >= getProxy().getServers().size()) {
                    it.remove();
                }
            }
        }
    }

    @EventHandler
    public void onPlayerLogin(PostLoginEvent event) {
        sendPlayerStatusChange(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler
    public void onPlayerLogout(PlayerDisconnectEvent event) {
        sendPlayerStatusChange(event.getPlayer().getUniqueId(), false);
    }

    public void sendPlayerStatusChange(UUID uuid, boolean isOnline) {
        for (ServerInfo serverInfo : ProxyServer.getInstance().getServers().values()) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(isOnline ? "joined" : "disconnected");
            out.writeUTF(uuid.toString());
            serverInfo.sendData("funnyguildsplayer", out.toByteArray());
        }
    }

    private void log(String message) {
        try {
            if (!new File(getDataFolder() + "/logs/").exists()) {
                new File(getDataFolder() + "/logs/").mkdirs();
            }

            message = message.replaceAll("§[0-f]", "");
            PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(new File(getDataFolder() + "/logs/"
                    + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".log"), true)));
            out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + message);
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendSingleMessage(UUID uuid, String message) {
        if (getProxy().getPlayer(uuid) != null) {
            getProxy().getPlayer(uuid).sendMessage(TextComponent.fromLegacyText(message));
        }
    }

    private void sendPlayerToGuildHome(UUID uuid, String guild, String homeServer, Server source)
            throws IOException {
        ServerInfo target = source.getInfo();
        if (!source.getInfo().getAddress().toString().split("/")[1].equals(homeServer)) {
            ProxiedPlayer player = getProxy().getPlayer(uuid);
            if (player != null) {
                if (new InetSocketAddress(homeServer.split(":")[0],
                        Integer.parseInt(homeServer.split(":")[1])).getAddress().isReachable(2000)) {
                    target = getTargetServer(homeServer);
                    if (target != null) {
                        player.connect(target);
                    } else {
                        getLogger().severe("Wystapil blad z polaczeniem z bungeecord.");
                    }
                } else {
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    out.writeUTF("homeServerUnreachable");
                    out.writeUTF(uuid.toString());
                    source.sendData("funnyguildserror", out.toByteArray());
                    return;
                }
            }
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("home");
        out.writeUTF(uuid.toString());
        out.writeUTF(guild);
        if (target != null) {
            target.sendData("funnyguildsplayer", out.toByteArray());
        }
    }

    private ServerInfo getTargetServer(String homeServerIP) {
        for (ServerInfo serverInfo : getProxy().getServers().values()) {
            if (serverInfo.getAddress().equals(new InetSocketAddress(homeServerIP.split(":")[0], Integer.parseInt(homeServerIP.split(":")[1])))) {
                return serverInfo;
            }
        }
        return null;
    }

    private class QueueingPluginMessage {
        private final String operation;
        private final String args;
        private final ByteArrayDataOutput out;

        private Set<String> sendTo = new HashSet<>();

        public QueueingPluginMessage(String operation, String args, ByteArrayDataOutput out) {
            this.operation = operation;
            this.args = args;
            this.out = out;
        }

        public boolean send(ServerInfo serverInfo) {
            if (!sendTo.contains(serverInfo.getName())) {
                sendTo.add(serverInfo.getName());
                serverInfo.sendData("funnyguilds" + operation, out.toByteArray());
                return true;
            }
            return false;
        }

        public String getCommand() {
            return operation + ":" + args;
        }

        public Set<String> getSendTo() {
            return sendTo;
        }
    }
}
