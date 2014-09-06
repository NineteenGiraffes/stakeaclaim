// $Id$
/*
 * StakeAClaim
 * Copyright (C) 2013 NineteenGiraffes <http://www.NineteenGiraffes.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.nineteengiraffes.stakeaclaim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.nineteengiraffes.stakeaclaim.PlayerStateManager.PlayerState;
import com.nineteengiraffes.stakeaclaim.stakes.Stake;
import com.nineteengiraffes.stakeaclaim.stakes.Stake.Status;
import com.nineteengiraffes.stakeaclaim.stakes.StakeManager;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.minecraft.util.commands.CommandPermissionsException;
import com.sk89q.wepif.PermissionsResolverManager;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.bukkit.BukkitUtil;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WGBukkit;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

public class SACUtil {

    // Get regions
    /**
     * Get the single 'claim' the player is in
     * 
     * @param player the player to get the location from
     * @param plugin the SAC plugin
     * @return the 'claim' the player is standing in
     * @throws CommandException no regions or not in a single 'claim'
     */
    public static ProtectedRegion getClaimStandingIn(Player player, StakeAClaimPlugin plugin) throws CommandException {

        final World world = player.getWorld();
        final RegionManager rgMgr = WGBukkit.getRegionManager(world);
        if (rgMgr == null) {
            throw new CommandException(ChatColor.YELLOW + "Regions are disabled in this world.");
        }

        final ConfigManager cfg = plugin.getGlobalManager();
        final WorldConfig wcfg = cfg.get(world);
        final Location loc = player.getLocation();

        ProtectedRegion claim = getClaimAtPoint(rgMgr, wcfg, new Vector(loc.getX(), loc.getY(), loc.getZ()));
 
        if (claim == null) {
            throw new CommandException("You are not in a single valid claim!");
        }

        return claim;
    }

    /**
     * Get the single 'claim' at a given point
     * 
     * @param rgMgr the region manager to look for the claim in
     * @param vector the location to look for the claim
     * @return the claim at {@code vector}, returns null if there are no claims there, or more than one
     */
    public static ProtectedRegion getClaimAtPoint(RegionManager rgMgr, WorldConfig wcfg, Vector vector) {

        final ApplicableRegionSet rgSet = rgMgr.getApplicableRegions(vector);
        final Pattern regexPat = Pattern.compile(wcfg.claimNameFilter);
        Matcher regexMat;
        ProtectedRegion claim = null;

        for (ProtectedRegion region : rgSet) {
            regexMat = regexPat.matcher(region.getId());
            if (regexMat.find()) {
                if (claim == null) {
                    claim = region;
                } else {
                    claim = null;
                    break;
                }
            }
        }

        return claim;
    }


    // Get Stakes
    /**
     * Get the pending stake for (@code player) if any
     * 
     * @param rgMgr the region manager to work with
     * @param sMgr the stake manager to work with
     * @param player the player to get the stake for
     * @return pending stake for the player, or null
     */
    public static Stake getPendingStake(RegionManager rgMgr, StakeManager sMgr, UUID playerUUID) {

        final Map<String, Stake> stakes = sMgr.getStakes();
        ArrayList<Stake> stakeList = new ArrayList<Stake>();
        ArrayList<Stake> removeList = new ArrayList<Stake>();
        ProtectedRegion claim;
        boolean save = false;

        for (Stake stake : stakes.values()) {
            if (stake.getStakeUUID() != null && stake.getStakeUUID() == playerUUID &&
                    stake.getStatus() != null && stake.getStatus() == Status.PENDING) {
                claim = rgMgr.getRegion(stake.getId());
                if (claim == null) {
                    removeList.add(stake);
                    continue;
                }
                int ownedCode = isRegionOwned(claim);
                if (ownedCode > 0) {
                    stake.setStatus(null);
                    stake.setStakeUUID(null);
                    save = true;
                } else {
                    stakeList.add(stake);
                }
            }
        }

        for (Stake stake : removeList) {
            stakes.remove(stake.getId());
            save = true;
        }

        if (stakeList.size() == 0) {
            if (save) sMgr.save();
            return null;
        } else {
            for (int i = 1; i < stakeList.size(); i++) {
                stakeList.get(i).setStatus(null);
                stakeList.get(i).setStakeUUID(null);
                save = true;
            }
            if (save) sMgr.save();
            return stakeList.get(0);
        }
    }


    // Permissions
    /**
     * Checks permissions.
     *
     * @param plugin the SAC plugin
     * @param sender The sender to check the permission on.
     * @param perm The permission to check the permission on.
     * @return whether {@code sender} has {@code perm}
     */
    public static boolean hasPermission(StakeAClaimPlugin plugin, CommandSender sender, String perm) {
        if (sender.isOp()) {
            if (sender instanceof Player) {
                if (plugin.getGlobalManager().get(((Player) sender).getWorld()).opPermissions) {
                    return true;
                }
            } else {
                return true;
            }
        }

        // Invoke the permissions resolver
        if (sender instanceof Player) {
            Player player = (Player) sender;
            return PermissionsResolverManager.getInstance().hasPermission(player.getWorld().getName(), player.getName(), perm);
        }

        return false;
    }

    /**
     * Checks permissions and throws an exception if permission is not met.
     *
     * @param plugin the SAC plugin
     * @param sender the sender to check the permission on.
     * @param perm the permission to check the permission on.
     * @throws CommandPermissionsException if {@code sender} doesn't have {@code perm}
     */
    public static void checkPermission(StakeAClaimPlugin plugin, CommandSender sender, String perm)
            throws CommandPermissionsException {
        if (!hasPermission(plugin, sender, perm)) {
            throw new CommandPermissionsException();
        }
    }

    /**
     * Check sender for permission on a claim.
     * 
     * @param plugin the SAC plugin
     * @param player the player to check the permission on.
     * @param command the command the permission is for
     * @param claim the claim to check owner and member permissions on
     * @return true if sender has permission
     * @throws CommandException 
     */
    public static boolean hasPerm(StakeAClaimPlugin plugin, Player player, String command, ProtectedRegion claim) throws CommandException {

        final LocalPlayer localPlayer = WGBukkit.getPlugin().wrapPlayer(player);
        final String id = claim.getId();

        if (claim.isOwner(localPlayer)) {
            return hasPermission(plugin, player, "stakeaclaim.claim." + command + ".own." + id.toLowerCase());
        } else if (claim.isMember(localPlayer)) {
            return hasPermission(plugin, player, "stakeaclaim.claim." + command + ".member." + id.toLowerCase());
        } else {
            return hasPermission(plugin, player, "stakeaclaim.claim." + command + "." + id.toLowerCase());
        }
    }

    /**
     * Check sender for permission on a claim and throws an exception if permission is not met.
     * 
     * @param plugin the SAC plugin
     * @param sender the sender to check the permission on.
     * @param command the command the permission is for
     * @param claim the claim to check owner and member permissions on
     * @throws CommandPermissionsException if the sender does not have permission
     */
    public static void checkPerm(StakeAClaimPlugin plugin, Player player, String command, ProtectedRegion claim) throws CommandPermissionsException {

        final LocalPlayer localPlayer = WGBukkit.getPlugin().wrapPlayer(player);
        final String id = claim.getId();

        if (claim.isOwner(localPlayer)) {
            checkPermission(plugin, player, "stakeaclaim.claim." + command + ".own." + id.toLowerCase());
        } else if (claim.isMember(localPlayer)) {
            checkPermission(plugin, player, "stakeaclaim.claim." + command + ".member." + id.toLowerCase());
        } else {
            checkPermission(plugin, player, "stakeaclaim.claim." + command + "." + id.toLowerCase());
        }
    }


    // Small checks
    /**
     * Checks to see if the sender is a player, otherwise throw an exception.
     *
     * @param sender The {@link CommandSender} to check
     * @return {@code sender} casted to a player
     * @throws CommandException if {@code sender} isn't a {@link Player}
     */
    public static Player checkPlayer(CommandSender sender) throws CommandException {
        if (sender instanceof Player) {
            return (Player) sender;
        } else {
            throw new CommandException("This command can not be done from the console!");
        }
    }

    /**
     * Check one region for owners. 
     * int > 1, has multiple owners. 
     * int < 0, has members but no owners.
     * 
     * @param region the region to check owners of
     * @return int error code / modified owners count
     */
    public static int isRegionOwned(ProtectedRegion region) {

        int owners = region.getOwners().size();
        if (owners == 1) {
            return owners + region.getOwners().getGroups().size();
        }
        if (owners == 0) {
            return owners - region.getMembers().size();
        }
        return owners;
    }


    // Offline player queries
    /**
     * Get an offline player from a player UUID
     * 
     * @param plugin the SakesAClaim plugin
     * @param playerUUID the player UUID
     * @return OfflinePlayer
     */
    public static OfflinePlayer offPlayer(StakeAClaimPlugin plugin, UUID playerUUID) {
        return plugin.getServer().getOfflinePlayer(playerUUID);
    }

    /**
     * Get an offline player from a player name
     * 
     * @param plugin the SakesAClaim plugin
     * @param playerName the player
     * @return OfflinePlayer
     */
    //@Deprecated
    //TODO find a better way to do this
    @SuppressWarnings("deprecation")
    public static OfflinePlayer offPlayer(StakeAClaimPlugin plugin, String playerName) {
        return plugin.getServer().getOfflinePlayer(playerName);
    }


    // Filters
    /**
     * Filters {@code fullList} with all non {@code null} filters
     * 
     * @param plugin the SAC plugin
     * @param fullList the list of regions to filter
     * @param world the world the list is from
     * <pre>
     * Filters:</pre>
     * @param joinList
     * @param id
     * @param owner
     * @param member
     * @param banned
     * @param pending
     * @param hasMembers
     * @param claimed
     * @param vip
     * @param absent
     * @param seen
     * @return filtered list
     * @throws CommandException if no claims matched filter(s)
     */
    public static LinkedHashMap<Integer, ProtectedRegion> filterList(
            StakeAClaimPlugin plugin, ArrayList<ProtectedRegion> fullList, World world, 
            LinkedHashMap<Integer, ProtectedRegion> joinList, String id, OfflinePlayer owner, OfflinePlayer member, Boolean typo, Boolean banned, 
            Boolean pending, Boolean hasMembers,  Boolean claimed, Boolean vip, Long absent, Long seen) throws CommandException {

            final ConfigManager cfg = plugin.getGlobalManager();
            final WorldConfig wcfg = cfg.get(world);
            final StakeManager sMgr = plugin.getGlobalStakeManager().get(world);
            // claims
            ArrayList<ProtectedRegion> tempList = new ArrayList<ProtectedRegion>();
            final Pattern regexPat = Pattern.compile(wcfg.claimNameFilter);
            Matcher regexMat;
            for (ProtectedRegion claim : fullList) {
                regexMat = regexPat.matcher(claim.getId());
                if (regexMat.find()) {
                    tempList.add(claim);
                }
            }
            if (tempList.isEmpty()) {
                throw new CommandException(ChatColor.YELLOW + "There are no claims in this world.");
            }
            fullList = tempList;

            // id
            if (id != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getId().equalsIgnoreCase(id)) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched '" + ChatColor.WHITE + id + ChatColor.YELLOW + "' for filter '" + 
                            ChatColor.WHITE + "id" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // owner
            if (owner != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getOwners().contains(owner.getUniqueId()) || 
                            claim.getOwners().contains(owner.getName().toLowerCase())) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched '" + ChatColor.WHITE + owner + ChatColor.YELLOW + "' for filter '" + 
                            ChatColor.WHITE + "owner" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // member
            if (member != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getMembers().contains(member.getUniqueId()) || 
                            claim.getMembers().contains(member.getName().toLowerCase())) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched '" + ChatColor.WHITE + member + ChatColor.YELLOW + "' for filter '" + 
                            ChatColor.WHITE + "member" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // typo
// remove typo filter when names are removed entirely
            if (typo != null) {

                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    boolean hasTypo = false;
                    for (String oneMember : claim.getMembers().getPlayers()) {
                        if (!offPlayer(plugin, oneMember).hasPlayedBefore()) {
                            hasTypo = true;
                            break;
                        }
                    }
                    for (String oneOwner : claim.getOwners().getPlayers()) {
                        if (!offPlayer(plugin, oneOwner).hasPlayedBefore()) {
                            hasTypo = true;
                            break;
                        }
                    }
                    if (hasTypo == typo) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched '" + ChatColor.WHITE + typo + ChatColor.YELLOW + "' for filter '" + 
                            ChatColor.WHITE + "typo" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // banned
            if (banned != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    boolean isBanned = false;
                    for (UUID oneMember : claim.getMembers().getUniqueIds()) {
                        if (offPlayer(plugin, oneMember).isBanned()) {
                            isBanned = true;
                            break;
                        }
                    }
                    for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                        if (offPlayer(plugin, oneOwner).isBanned()) {
                            isBanned = true;
                            break;
                        }
                    }

// remove 2 for loops when names are removed entirely
                    for (String oneMember : claim.getMembers().getPlayers()) {
                        if (offPlayer(plugin, oneMember).isBanned()) {
                            isBanned = true;
                            break;
                        }
                    }
                    for (String oneOwner : claim.getOwners().getPlayers()) {
                        if (offPlayer(plugin, oneOwner).isBanned()) {
                            isBanned = true;
                            break;
                        }
                    }

                    if (isBanned == banned) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched '" + ChatColor.WHITE + banned + ChatColor.YELLOW + "' for filter '" + 
                            ChatColor.WHITE + "banned" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // pending
            if (pending != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (sMgr.getStake(claim).getStatus() == Status.PENDING && pending) {
                        tempList.add(claim);
                    } else if (sMgr.getStake(claim).getStatus() != Status.PENDING && !pending) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched '" + ChatColor.WHITE + pending.toString() + 
                            ChatColor.YELLOW + "' for filter '" + ChatColor.WHITE + "pending" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // has members
            if (hasMembers != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getMembers().size() > 0 && hasMembers) {
                        tempList.add(claim);
                    } else if (claim.getMembers().size() <= 0 && !hasMembers) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched '" + ChatColor.WHITE + hasMembers.toString() + 
                            ChatColor.YELLOW + "' for filter '" + ChatColor.WHITE + "member" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // claimed
            if (claimed != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getOwners().size() > 0 && claimed) {
                        tempList.add(claim);
                    } else if (claim.getOwners().size() <= 0 && !claimed) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched '" + ChatColor.WHITE + claimed.toString() + 
                            ChatColor.YELLOW + "' for filter '" + ChatColor.WHITE + "owner" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // vip
            if (vip != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (sMgr.getStake(claim).getVIP() == vip) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched '" + ChatColor.WHITE + vip.toString() + 
                            ChatColor.YELLOW + "' for filter '" + ChatColor.WHITE + "vip" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // absent
            if (absent != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    List<Long> times = new ArrayList<Long>();
                    for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                        Long online = offPlayer(plugin, oneOwner).getLastPlayed();
                        if (online > 0) {
                            times.add(online);
                        }
                    }

// remove for loop when names are removed entirely
                    for (String oneOwner : claim.getOwners().getPlayers()) {
                        Long online = offPlayer(plugin, oneOwner).getLastPlayed();
                        if (online > 0) {
                            times.add(online);
                        }
                    }

                    if (!times.isEmpty() && Collections.max(times) < absent) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched filter '" + ChatColor.WHITE + "absent" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }

            // seen
            if (seen != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    List<Long> times = new ArrayList<Long>();
                    for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                        Long online = offPlayer(plugin, oneOwner).getLastPlayed();
                        if (online > 0) {
                            times.add(online);
                        }
                    }

// remove for loop when names are removed entirely
                    for (String oneOwner : claim.getOwners().getPlayers()) {
                        Long online = offPlayer(plugin, oneOwner).getLastPlayed();
                        if (online > 0) {
                            times.add(online);
                        }
                    }

                    if (!times.isEmpty() && Collections.max(times) > seen) {
                        tempList.add(claim);
                    }
                }
                if (tempList.isEmpty()) {
                    throw new CommandException(ChatColor.YELLOW + "No claims matched filter '" + ChatColor.WHITE + "seen" + ChatColor.YELLOW + "'");
                }
                fullList = tempList;
            }
            // end filtering

            LinkedHashMap<Integer, ProtectedRegion> regionList = new LinkedHashMap<Integer, ProtectedRegion>();
            if (joinList != null) {
                regionList = joinList;
            }

            int index = regionList.size();
            for (ProtectedRegion region : fullList) {
                if (!regionList.containsValue(region)) {
                    regionList.put(index, region);
                    index++;
                }
            }

            return regionList;

    }

    /**
     * Filters all regions with all non {@code null} filters
     * 
     * @param plugin the SAC plugin
     * @param rgMgr the region manager to work with
     * @param world the world the list is from
     * <pre>
     * Filters:</pre>
     * @param joinList
     * @param id
     * @param owner
     * @param member
     * @param banned
     * @param pending
     * @param hasMembers
     * @param claimed
     * @param vip
     * @param absent
     * @param seen
     * @return filtered list, may be empty
     */
    public static LinkedHashMap<Integer, ProtectedRegion> filterList(
            StakeAClaimPlugin plugin, RegionManager rgMgr, World world, 
            LinkedHashMap<Integer, ProtectedRegion> joinList, String id, OfflinePlayer owner, OfflinePlayer member, Boolean typo, Boolean banned, 
            Boolean pending, Boolean hasMembers,  Boolean claimed, Boolean vip, Long absent, Long seen) {

            ArrayList<ProtectedRegion> fullList = new ArrayList<ProtectedRegion>();
            fullList.addAll(rgMgr.getRegions().values());
            final ConfigManager cfg = plugin.getGlobalManager();
            final WorldConfig wcfg = cfg.get(world);
            final StakeManager sMgr = plugin.getGlobalStakeManager().get(world);

            // claims
            ArrayList<ProtectedRegion> tempList = new ArrayList<ProtectedRegion>();
            final Pattern regexPat = Pattern.compile(wcfg.claimNameFilter);
            Matcher regexMat;
            for (ProtectedRegion claim : fullList) {
                regexMat = regexPat.matcher(claim.getId());
                if (regexMat.find()) {
                    tempList.add(claim);
                }
            }
            fullList = tempList;

            // id
            if (id != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getId().equalsIgnoreCase(id)) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // owner
            if (owner != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getOwners().contains(owner.getUniqueId()) || 
                            claim.getOwners().contains(owner.getName().toLowerCase())) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // member
            if (member != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getMembers().contains(member.getUniqueId()) || 
                            claim.getMembers().contains(member.getName().toLowerCase())) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // typo
// remove typo filter when names are removed entirely
            if (typo != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    boolean hasTypo = false;
                    for (String oneMember : claim.getMembers().getPlayers()) {
                        if (!offPlayer(plugin, oneMember).hasPlayedBefore()) {
                            hasTypo = true;
                            break;
                        }
                    }
                    for (String oneOwner : claim.getOwners().getPlayers()) {
                        if (!offPlayer(plugin, oneOwner).hasPlayedBefore()) {
                            hasTypo = true;
                            break;
                        }
                    }
                    if (hasTypo == typo) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // banned
            if (banned != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    boolean isBanned = false;
                    for (UUID oneMember : claim.getMembers().getUniqueIds()) {
                        if (offPlayer(plugin, oneMember).isBanned()) {
                            isBanned = true;
                            break;
                        }
                    }
                    for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                        if (offPlayer(plugin, oneOwner).isBanned()) {
                            isBanned = true;
                            break;
                        }
                    }

// remove 2 for loops when names are removed entirely
                    for (String oneMember : claim.getMembers().getPlayers()) {
                        if (offPlayer(plugin, oneMember).isBanned()) {
                            isBanned = true;
                            break;
                        }
                    }
                    for (String oneOwner : claim.getOwners().getPlayers()) {
                        if (offPlayer(plugin, oneOwner).isBanned()) {
                            isBanned = true;
                            break;
                        }
                    }

                    if (isBanned == banned) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // pending
            if (pending != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (sMgr.getStake(claim).getStatus() == Status.PENDING && pending) {
                        tempList.add(claim);
                    } else if (sMgr.getStake(claim).getStatus() != Status.PENDING && !pending) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // has members
            if (hasMembers != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getMembers().size() > 0 && hasMembers) {
                        tempList.add(claim);
                    } else if (claim.getMembers().size() <= 0 && !hasMembers) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // claimed
            if (claimed != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (claim.getOwners().size() > 0 && claimed) {
                        tempList.add(claim);
                    } else if (claim.getOwners().size() <= 0 && !claimed) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // vip
            if (vip != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    if (sMgr.getStake(claim).getVIP() == vip) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // absent
            if (absent != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    List<Long> times = new ArrayList<Long>();
                    for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                        Long online = offPlayer(plugin, oneOwner).getLastPlayed();
                        if (online > 0) {
                            times.add(online);
                        }
                    }

// remove for loop when names are removed entirely
                    for (String oneOwner : claim.getOwners().getPlayers()) {
                        Long online = offPlayer(plugin, oneOwner).getLastPlayed();
                        if (online > 0) {
                            times.add(online);
                        }
                    }

                    if (!times.isEmpty() && Collections.max(times) < absent) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }

            // seen
            if (seen != null) {
                tempList = new ArrayList<ProtectedRegion>();
                for (ProtectedRegion claim : fullList) {
                    List<Long> times = new ArrayList<Long>();
                    for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                        Long online = offPlayer(plugin, oneOwner).getLastPlayed();
                        if (online > 0) {
                            times.add(online);
                        }
                    }

// remove for loop when names are removed entirely
                    for (String oneOwner : claim.getOwners().getPlayers()) {
                        Long online = offPlayer(plugin, oneOwner).getLastPlayed();
                        if (online > 0) {
                            times.add(online);
                        }
                    }

                    if (!times.isEmpty() && Collections.max(times) > seen) {
                        tempList.add(claim);
                    }
                }
                fullList = tempList;
            }
            // end filtering

            LinkedHashMap<Integer, ProtectedRegion> regionList = new LinkedHashMap<Integer, ProtectedRegion>();
            if (joinList != null) {
                regionList = joinList;
            }

            int index = regionList.size();
            for (ProtectedRegion region : fullList) {
                if (!regionList.containsValue(region)) {
                    regionList.put(index, region);
                    index++;
                }
            }

            return regionList;

    }


    // Display
    /**
     * Display to {@code sender} all claims in {@code fullList}
     * 
     * @param sender the sender to display the list to
     * @param fullList the list to display
     * @param sMgr the stake manager to work with
     * @param world the world the list is for
     * @param page the page of the list to show (one page = 10 claims)
     */
    public static void displayList(StakeAClaimPlugin plugin, CommandSender sender, LinkedHashMap<Integer, ProtectedRegion> fullList, StakeManager sMgr, World world, int page) {
        final int totalSize = fullList.size();
        if (totalSize < 1) {
            return;
        }
        final int pageSize = 10;
        final int pages = (int) Math.ceil(totalSize / (float) pageSize);
        final boolean isPlayer = (sender instanceof Player);
        if (page + 1 > pages) {
            page = pages - 1;
        }

        if (pages > 1) {
            if (hasPermission(plugin, sender, "stakeaclaim.sac.search") && isPlayer) {
                StringBuilder message = new StringBuilder("tellraw " + sender.getName() + " {text:'Claim list");
                if (((Player) sender).getWorld() != world) {
                    message.append(" for ',color:yellow,extra:[");
                    message.append("{text:'" + world.getName() + "',color:blue},");
                    message.append("': ',");
                } else {
                    message.append(": ',color:yellow,extra:[");
                }
                message.append("{text:'[page ',color:gray,extra:[");
                if (page > 0) {
                    message.append("{text:'<< ',color:gold,");
                    message.append("clickEvent:{action:run_command,value:'/sac search refine page " + page + "'}},");
                }
                message.append("'" + (page + 1) + " of " + pages + "'");
                if ((page +1) < pages) {
                    message.append(",{text:' >>',color:gold,");
                    message.append("clickEvent:{action:run_command,value:'/sac search refine page " + (page + 2) + "'}}");
                }
                message.append(",']']}]}");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), message.toString());
            } else {
                if (isPlayer && ((Player) sender).getWorld() == world) {
                    sender.sendMessage(ChatColor.YELLOW + "Claim list: " + ChatColor.GRAY + "(page " + (page + 1) + " of " + pages + ")");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "Claim list for " + ChatColor.BLUE + world.getName() + ChatColor.YELLOW + ": " + 
                            ChatColor.GRAY + "(page " + (page + 1) + " of " + pages + ")");
                }
            }
        } else {
            if (isPlayer && ((Player) sender).getWorld() == world) {
                sender.sendMessage(ChatColor.YELLOW + "Claim list:");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "Claim list for " + ChatColor.BLUE + world.getName() + ChatColor.YELLOW + ":");
            }
        }

        if (page < pages) {
            for (int i = page * pageSize; i < page * pageSize + pageSize; i++) {
                if (i >= totalSize) {
                    break;
                }
                if (isPlayer) {
                    displayClaim(String.valueOf(i + 1), fullList.get(i), sMgr.getStake(fullList.get(i)), sender, plugin, world);
                } else {
                    displayClaim(String.valueOf(i + 1), fullList.get(i), sMgr.getStake(fullList.get(i)), sender, plugin);
                }
            }
        }
    }

    /**
     * Displays one {@code claim} to {@code sender} on one line
     * 
     * @param index the index to show for the claim
     * @param claim the claim to display
     * @param stake the stake for the claim
     * @param sender the sender to display the claim to
     * @return true if the claim is unclaimed
     */
    public static boolean displayClaim(String index, ProtectedRegion claim, Stake stake, CommandSender sender, StakeAClaimPlugin plugin) {

        boolean open = false;
        StringBuilder message = new StringBuilder(ChatColor.YELLOW + "# " + index + ": " + formatID(stake));
        if (stake.getClaimName() != null) {
            message.append(ChatColor.LIGHT_PURPLE + " '" + stake.getClaimName() + "'");
        }

        int ownedCode = isRegionOwned(claim);
        if (ownedCode <= 0) {
            if (stake.getStatus() != null && stake.getStatus() == Status.PENDING && stake.getStakeUUID() != null) {
                message.append(ChatColor.YELLOW + " pending for " + formatPlayer(offPlayer(plugin, stake.getStakeUUID())));
            } else {
                message.append(" " + ChatColor.GRAY + "Unclaimed");
                open = true;
            }
        } else {
            for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                message.append(" " + formatPlayer(offPlayer(plugin, oneOwner)));
            }

// remove for loop when names are removed entirely
            for (String oneOwner : claim.getOwners().getPlayers()) {
                message.append(" " + formatPlayer(oneOwner));
            }

        }
        if (claim.getMembers().size() > 0) {
            boolean isBanned = false;
            boolean hasTypo = false;
            for (UUID oneMember : claim.getMembers().getUniqueIds()) {
                OfflinePlayer offlineMember = offPlayer(plugin, oneMember);
                if (offlineMember.isBanned()) {
                    isBanned = true;
                    break;
                } else if (!offlineMember.hasPlayedBefore()) {
                    hasTypo = true;
                }
            }

// remove for loop when names are removed entirely
//            for (String oneMember : claim.getMembers().getPlayers()) {
//                OfflinePlayer offlineMember = offPlayer(plugin, oneMember);
//                if (offlineMember.isBanned()) {
//                    isBanned = true;
//                    break;
//                } else if (!offlineMember.hasPlayedBefore()) {
//                    hasTypo = true;
//                }
//            }

            message.append(" " + ChatColor.YELLOW + " : " + (isBanned ? ChatColor.DARK_RED : 
                    (hasTypo ? ChatColor.RED : ChatColor.GREEN)) + claim.getMembers().size());
        }

        if (claim.getFlag(DefaultFlag.ENTRY) != null && claim.getFlag(DefaultFlag.ENTRY) == State.DENY) {
            message.append(ChatColor.RED + "" + ChatColor.ITALIC + " Private!");
        }

        sender.sendMessage(message.toString());
        return open;
    }

    /**
     * Displays one {@code claim} to {@code sender} on one line with clicks
     * 
     * @param index the index to show for the claim
     * @param claim the claim to display
     * @param stake the stake for the claim
     * @param sender the sender to display the claim to
     * @return true if the claim is unclaimed
     */
    public static boolean displayClaim(String index, ProtectedRegion claim, Stake stake, CommandSender sender, StakeAClaimPlugin plugin, World world) {

        boolean open = false;
        StringBuilder message = new StringBuilder("tellraw " + sender.getName() + " {text:'# " + index + ":',color:yellow,extra:[");
        if (hasPermission(plugin, sender, "stakeaclaim.sac.claim")) {
            message.append(formatIdJSON(stake, world));
        } else {
            message.append(formatIdJSON(stake, null));
        }
        if (stake.getClaimName() != null) {
            message.append(",{text:' \\\'" + stake.getClaimName() + "\\\'',color:light_purple}");
        }

        boolean accept = false;
        boolean deny = false;
        int ownedCode = isRegionOwned(claim);
        if (ownedCode <= 0) {
            if (stake.getStatus() != null && stake.getStatus() == Status.PENDING && stake.getStakeUUID() != null) {
                message.append(",' pending for',");
                if (hasPermission(plugin, sender, "stakeaclaim.sac.user")) {
                    message.append(formatPlayerJSON(world, offPlayer(plugin, stake.getStakeUUID())));
                } else {
                    message.append(formatPlayerJSON(null, offPlayer(plugin, stake.getStakeUUID())));
                }
                accept = hasPermission(plugin, sender, "stakeaclaim.sac.do.accept");
                deny = hasPermission(plugin, sender, "stakeaclaim.sac.do.deny");
            } else {
                message.append(",{text:' Unclaimed',color:gray}");
                open = true;
            }
        } else {
            for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                message.append(",");
                if (hasPermission(plugin, sender, "stakeaclaim.sac.user")) {
                    message.append(formatPlayerJSON(world, offPlayer(plugin, oneOwner)));
                } else {
                    message.append(formatPlayerJSON(null, offPlayer(plugin, oneOwner)));
                }
            }

// remove for loop when names are removed entirely
            for (String oneOwner : claim.getOwners().getPlayers()) {
                message.append(",");
                message.append(formatPlayerJSON(oneOwner));
            }

        }
        if (claim.getMembers().size() > 0) {
            boolean isBanned = false;
            boolean hasTypo = false;
            for (UUID oneMember : claim.getMembers().getUniqueIds()) {
                OfflinePlayer offlineMember = offPlayer(plugin, oneMember);
                if (offlineMember.isBanned()) {
                    isBanned = true;
                    break;
                } else if (!offlineMember.hasPlayedBefore()) {
                    hasTypo = true;
                }
            }

// remove for loop when names are removed entirely
//            for (String oneMember : claim.getMembers().getPlayers()) {
//                OfflinePlayer offlineMember = offPlayer(plugin, oneMember);
//                if (offlineMember.isBanned()) {
//                    isBanned = true;
//                    break;
//                } else if (!offlineMember.hasPlayedBefore()) {
//                    hasTypo = true;
//                }
//            }

            message.append(",{text:' :'}");
            message.append(",{text:' " + claim.getMembers().size() + "',color:" + (isBanned ? "dark_red" : (hasTypo ? "red" : "green")) + "}");
        }

        if (claim.getFlag(DefaultFlag.ENTRY) != null && claim.getFlag(DefaultFlag.ENTRY) == State.DENY) {
            message.append(",{text:' Private!',color:red,italic:true}");
        }

        if (accept || deny) {
            message.append(",{text:' |',color:gold,bold:true}");
        }
        if (accept) {
            message.append(",{text:'Accept',color:dark_green,");
            message.append("clickEvent:{action:run_command,value:'/sac do accept " + index + "'}}");
            message.append(",{text:'|',color:gold,bold:true}");
        }
        if (deny) {
            message.append(",{text:'Deny',color:dark_red,");
            message.append("clickEvent:{action:run_command,value:'/sac do deny " + index + "'}}");
            message.append(",{text:'|',color:gold,bold:true}");
        }

        message.append("]}");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), message.toString());
        return open;
    }

    /**
     * Displays one detailed {@code claim} to {@code sender} with clicks
     * 
     * @param sender the sender to display the claim to
     * @param claim the claim to display
     * @param stake the stake for the claim
     * @param wcfg the world config to work with
     * @param world the world the claim is in
     */
    public static void displayClaim(WorldConfig wcfg, ProtectedRegion claim, Stake stake, CommandSender sender, StakeAClaimPlugin plugin, World world, Integer item) {

        final boolean isPlayer = (sender instanceof Player);
        sender.sendMessage(ChatColor.GRAY + 
                "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550" + 
                " Claim Info " + 
                "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        if (stake.getClaimName() != null) {
            sender.sendMessage(ChatColor.WHITE + "Name: " + ChatColor.LIGHT_PURPLE + " '" + stake.getClaimName() + "'");
        }
        
        if (hasPermission(plugin, sender, "stakeaclaim.sac.search") && isPlayer) {
            StringBuilder message = new StringBuilder("tellraw " + sender.getName() + " {text:'Location:',color:yellow,extra:[");
            message.append("{text:' " + stake.getId() + "',");
            message.append("clickEvent:{action:run_command,value:'/sac search id " + stake.getId() + " world " + world.getName() + "'},");
            message.append("color:" + (stake.getVIP() ? "aqua" : "white") + "}");
            if (claim.getFlag(DefaultFlag.ENTRY) == State.DENY) {
                message.append(",{text:' Private!',color:red}");
            } else if (stake.getVIP()) {
                message.append(",{text:' " + wcfg.VIPs + " claim!'}");
            }
            message.append("]}");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), message.toString());
        } else {
            StringBuilder message = new StringBuilder(ChatColor.YELLOW + "Location: " + formatID(stake));
            if (claim.getFlag(DefaultFlag.ENTRY) == State.DENY) {
                message.append(ChatColor.RED + " " + ChatColor.ITALIC + "Private!");
            } else if (stake.getVIP()) {
                message.append(ChatColor.YELLOW + " " + wcfg.VIPs + " claim!");
            }
            sender.sendMessage(message.toString());
        }

        if (stake.getStatus() != null && stake.getStatus() == Status.PENDING && stake.getStakeUUID() != null) {
            final OfflinePlayer offlinePlayer = offPlayer(plugin, stake.getStakeUUID());
            if (isPlayer) {
                StringBuilder message = new StringBuilder("tellraw " + sender.getName() + " {text:'Pending stake by:',color:dark_green,extra:[");
                if (hasPermission(plugin, sender, "stakeaclaim.sac.user")) {
                    message.append(formatPlayerJSON(world, offlinePlayer));
                } else {
                    message.append(formatPlayerJSON(null, offlinePlayer));
                }
                if (item != null) {
                    boolean accept = hasPermission(plugin, sender, "stakeaclaim.sac.do.accept");
                    boolean deny = hasPermission(plugin, sender, "stakeaclaim.sac.do.deny");
                    if (accept || deny) {
                        message.append(",{text:' |',color:gold,bold:true}");
                    }
                    if (accept) {
                        message.append(",{text:'Accept',color:dark_green,");
                        message.append("clickEvent:{action:run_command,value:'/sac do accept " + (item + 1) + "'}}");
                        message.append(",{text:'|',color:gold,bold:true}");
                    }
                    if (deny) {
                        message.append(",{text:'Deny',color:dark_red,");
                        message.append("clickEvent:{action:run_command,value:'/sac do deny " + (item + 1) + "'}}");
                        message.append(",{text:'|',color:gold,bold:true}");
                    }
                }

                message.append("]}");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), message.toString());
            } else {
                sender.sendMessage(ChatColor.DARK_GREEN + "Pending stake by: " + formatPlayer(offlinePlayer));
            }
        }

        if (claim.getOwners().size() != 0) {
            if (hasPermission(plugin, sender, "stakeaclaim.sac.user") && isPlayer) {
                StringBuilder message = new StringBuilder("tellraw " + sender.getName() + 
                        " {text:'Owner" + (claim.getOwners().size() == 1 ? ":" : "s:") + "',color:dark_green,extra:[");
                boolean first = true;
                for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                    if (!first ) {
                        message.append(",");
                    }
                    message.append(formatPlayerJSON(world, offPlayer(plugin, oneOwner)));
                    first = false;
                }

// remove for loop when names get removed entirely
                for (String oneOwner : claim.getOwners().getPlayers()) {
                    if (!first ) {
                        message.append(",");
                    }
                    message.append(formatPlayerJSON(oneOwner));
                    first = false;
                }

                message.append("]}");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), message.toString());
            } else {
                StringBuilder message = new StringBuilder(ChatColor.DARK_GREEN + "Owner" + (claim.getOwners().size() == 1 ? ":" : "s:"));
                for (UUID oneOwner : claim.getOwners().getUniqueIds()) {
                    message.append(" " + formatPlayer(offPlayer(plugin, oneOwner)));
                }

// remove for loop when names get removed entirely
                for (String oneOwner : claim.getOwners().getPlayers()) {
                    message.append(" " + formatPlayer(oneOwner));
                }

                sender.sendMessage(message.toString());
            }
        }

        if (claim.getMembers().size() != 0) {
            if (hasPermission(plugin, sender, "stakeaclaim.sac.user") && isPlayer) {
                StringBuilder message = new StringBuilder("tellraw " + sender.getName() + 
                        " {text:'Member" + (claim.getMembers().getPlayers().size() == 1 ? ":" : "s:") + "',color:dark_green,extra:[");
                boolean first = true;
                for (String oneMember : claim.getMembers().getPlayers()) {
                    if (!first ) {
                        message.append(",");
                    }
                    message.append(formatPlayerJSON(oneMember));
                    first = false;
                }
                message.append("]}");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), message.toString());
            } else {
                StringBuilder message = new StringBuilder(ChatColor.DARK_GREEN + "Member" + (claim.getMembers().getPlayers().size() == 1 ? ":" : "s:"));
                for (String oneMember : claim.getMembers().getPlayers()) {
                    message.append(" " + formatPlayer(oneMember));
                }
                sender.sendMessage(message.toString());
            }
        }

        final BlockVector min = claim.getMinimumPoint();
        final BlockVector max = claim.getMaximumPoint();
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Bounds:" + ChatColor.GOLD + 
                " (" + min.getBlockX() + "," + min.getBlockY() + "," + min.getBlockZ() + ") -> " + 
                " (" + max.getBlockX() + "," + max.getBlockY() + "," + max.getBlockZ() + ")");

        String blocks;
        if (claim.volume() < 1000) {
            blocks = claim.volume() + " blocks.";
        } else if (claim.volume() > 999999) {
            blocks = "~" + Integer.valueOf(claim.volume() / 1000000) + "m blocks.";
        } else {
            blocks = "~" + Integer.valueOf(claim.volume() / 1000) + "k blocks.";
        }

        if (hasPermission(plugin, sender, "stakeaclaim.sac.goto") && isPlayer) {
            StringBuilder message = new StringBuilder("tellraw " + sender.getName() + " {text:'Size:',color:light_purple,extra:[");
            message.append("{text:' " + blocks + "  ',color:gold}");
            message.append(",{text:'> GOTO <',");
            message.append("clickEvent:{action:run_command,value:'/sac goto " + claim.getId() + " " + world.getName() + "'},");
            message.append("color:yellow}");
            message.append("]}");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), message.toString());
        } else {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Size: " + ChatColor.GOLD + blocks);
        }

        if (isPlayer && ((Player) sender).getWorld() == world) {
        } else {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "World: " + ChatColor.BLUE + world.getName());
        }
    }

    /**
     * Display claims and info about one player
     * 
     * @param plugin the SAC plugin
     * @param sender the sender to display to
     * @param rgMgr the region manager to work with
     * @param world the world to display claims from
     * @param playerUUID the player whom to display
     */
    public static void displayPlayer(StakeAClaimPlugin plugin, CommandSender sender, RegionManager rgMgr, World world, OfflinePlayer offlinePlayer){

        final PlayerState state = plugin.getPlayerStateManager().getState(sender);
        final StakeManager sMgr = plugin.getGlobalStakeManager().get(world);
        LinkedHashMap<Integer, ProtectedRegion> regionList = new LinkedHashMap<Integer, ProtectedRegion>();

        sender.sendMessage(ChatColor.GRAY + 
                "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550" + 
                " Player Info " + 
                "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        sender.sendMessage(ChatColor.WHITE + "Name: " + formatPlayer(offlinePlayer));
        StringBuilder message = new StringBuilder(ChatColor.LIGHT_PURPLE + "Last on: ");
        if (offlinePlayer.isOnline() && offlinePlayer instanceof Player) {
            World inWorld = ((Player) offlinePlayer).getWorld();
            message.append(ChatColor.GOLD + "Online now in " + ChatColor.BLUE + inWorld.getName() + ChatColor.GOLD + ".");
        } else if (!offlinePlayer.hasPlayedBefore()) {
            message.append(ChatColor.GOLD + "Never.");
        } else {
            int hour = (int) ((System.currentTimeMillis() - offlinePlayer.getLastPlayed()) / 1000 / 60 / 60);
            int day = hour / 24;
            if (hour < 1) {
                message.append(ChatColor.GOLD + "Less than " + ChatColor.WHITE + "1" + ChatColor.GOLD + " hour ago.");
            } else if (day < 1) {
                message.append(ChatColor.WHITE + "" + hour + ChatColor.GOLD + " hour" + (hour == 1 ? "" : "s") + " ago.");
            } else if (day > 6) {
                message.append(ChatColor.WHITE + "" + day + ChatColor.GOLD + " day" + (day == 1 ? "" : "s") + " ago.");
            } else {
                hour = hour - (day * 24);
                message.append(ChatColor.WHITE + "" + day + ChatColor.GOLD + " day" + (day == 1 ? ", " : "s, ") + 
                        ChatColor.WHITE + hour + ChatColor.GOLD + " hour" + (hour == 1 ? "" : "s") + " ago.");
            }
        }
        sender.sendMessage(message.toString());

        message = new StringBuilder(ChatColor.YELLOW + "Claims: ");
        Stake stake = getPendingStake(rgMgr, sMgr, offlinePlayer.getUniqueId());
        int pending = 0;
        Integer own = 0;
        if (stake != null) {
            regionList.put(0,rgMgr.getRegion(stake.getId()));
            message.append(ChatColor.WHITE + "1 " + ChatColor.DARK_GREEN + "pending, ");
            pending++;
        }

        regionList = filterList(plugin, rgMgr, world, 
                regionList, null, offlinePlayer, null, null, null, null, null, null, null, null, null);
        own = regionList.size() - pending;
        message.append(ChatColor.WHITE + own.toString() + ChatColor.DARK_GREEN + " owned, member of ");

        regionList = filterList(plugin, rgMgr, world, 
                regionList, null, null, offlinePlayer, null, null, null, null, null, null, null, null);
        sender.sendMessage(message.toString() + ChatColor.WHITE + (regionList.size() - pending - own) + ChatColor.DARK_GREEN + ".");

        displayList(plugin, sender, regionList, sMgr, world, 0);
        state.regionList = regionList;
        state.listWorld = world;
    }


    // Formatting
        /**
     * Format player name as name only (no UUID)
     * 
     * @param playerName the player's name to format
     * @return formated player name
     */
    public static String formatPlayer(String playerName){
        return new StringBuilder(ChatColor.GREEN + "~" + playerName).toString();
    }

        /**
     * Format player name as name only (no UUID)
     * 
     * @param playerName the player's name to format
     * @return formated player name
     */
    public static String formatPlayerJSON(String playerName){
        return new StringBuilder("{text:' ~" + playerName + "',color:green}").toString();
    }

    /**
     * Format player name with banned, typo and online formats
     * 
     * @param offlinePlayer the player to format
     * @return formated player name
     */
    public static String formatPlayer(OfflinePlayer offlinePlayer){
        return new StringBuilder((offlinePlayer.isBanned() ? ChatColor.DARK_RED : 
                                (offlinePlayer.hasPlayedBefore() ? ChatColor.GREEN : ChatColor.RED)) + 
                                (offlinePlayer.isOnline() ? "*" : "") + offlinePlayer.getName()).toString();
    }

    /**
     * Format player name with banned, typo and online formats and click
     * 
     * @param world the world use in the click, null for no click
     * @param offlinePlayer the player to format
     * @return formated player name
     */
    public static String formatPlayerJSON(World world, OfflinePlayer offlinePlayer){
        StringBuilder player = new StringBuilder("{text:' " + (offlinePlayer.isOnline() ? "*" : "") + offlinePlayer.getName() + "',");
        if (world != null) {
            player.append("clickEvent:{action:run_command,value:'/sac user " + offlinePlayer.getName() + " " + world.getName() + "'},");
        }
        player.append("color:" + (offlinePlayer.isBanned() ? "dark_red" : (offlinePlayer.hasPlayedBefore() ? "green" : "red")) + "}");
        return player.toString();
    }

    /**
     * Format ID with VIP
     * 
     * @param stake the stake whose ID to format
     * @return formated ID
     */
    public static String formatID(Stake stake){
        return new StringBuilder((stake.getVIP() ? ChatColor.AQUA : ChatColor.WHITE) + stake.getId()).toString();
    }

    /**
     * Format ID with VIP and click
     * 
     * @param stake the stake whose ID to format
     * @return formated ID
     */
    public static String formatIdJSON(Stake stake, World world){
        StringBuilder id = new StringBuilder("{text:' " + stake.getId() + "',");
        if (world != null) {
            id.append("clickEvent:{action:run_command,value:'/sac claim " + stake.getId() + " " + world.getName() + "'},");
        }
        id.append("color:" + (stake.getVIP() ? "aqua" : "white") + "}");
        return id.toString();
    }


    // Warp tools
    /**
     *  Warp {@code player} to {@code claim}
     * 
     * @param claim the claim to warp to
     * @param stake the stake for the claim
     * @param player the player to warp
     * @param forceSpawn toggle spawn only warp
     * @return claim warped to, will be null if you did not warp
     */
    public static ProtectedRegion warpTo(StakeAClaimPlugin plugin, World world, ProtectedRegion claim, Stake stake, Player player, boolean forceSpawn){

        if (claim.getFlag(DefaultFlag.TELE_LOC)!= null && !forceSpawn) {
            player.teleport(BukkitUtil.toLocation(claim.getFlag(DefaultFlag.TELE_LOC)));
            player.sendMessage(ChatColor.YELLOW + "Gone to:");
            displayClaim("", claim, stake, player, plugin, world);
        } else if (claim.getFlag(DefaultFlag.SPAWN_LOC)!= null) {
            player.teleport(BukkitUtil.toLocation(claim.getFlag(DefaultFlag.SPAWN_LOC)));
            player.sendMessage(ChatColor.YELLOW + "Gone to spawn of:");
            displayClaim("", claim, stake, player, plugin, world);
        } else {
            player.sendMessage(ChatColor.YELLOW + "No warp set for " + formatID(stake) + "!");
            return null;
        }

        return claim;
    }

    /**
     *  Warp {@code sender} to remembered warp if there is one
     * 
     * @param plugin the SAC plugin
     * @param args the args from the command
     * @param sender the player to warp
     * @param spawn true to use the claim's spawn not the player set warp point
     * @return false if there was no remembered warp
     * @throws CommandException if sender is not a {@link Player} or if there is no remembered warp
     */
    public static boolean gotoRememberedWarp(StakeAClaimPlugin plugin, CommandContext args, CommandSender sender, boolean spawn) throws CommandException {

        final Player player = checkPlayer(sender);
        final PlayerState state = plugin.getPlayerStateManager().getState(sender);
        final ConfigManager cfg = plugin.getGlobalManager();

        if (state.lastWarp != null && state.warpWorld != null) {
            final WorldConfig wcfg = cfg.get(state.warpWorld);
            if (!wcfg.useStakes) {
                throw new CommandException(ChatColor.YELLOW + "Stakes are disabled in this world.");
            }
            final StakeManager sMgr = plugin.getGlobalStakeManager().get(state.warpWorld);

            state.lastWarp = warpTo(plugin, state.warpWorld, state.lastWarp, sMgr.getStake(state.lastWarp), player, spawn);
            if (state.lastWarp == null) {
                state.warpWorld = null;
            }
            return true;
        } else {
            return false;
        }
    }

}
