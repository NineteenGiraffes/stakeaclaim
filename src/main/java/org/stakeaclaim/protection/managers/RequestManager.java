// $Id$
/*
 * StakeAClaim
 * Copyright (C) 2010 sk89q <http://www.sk89q.com>
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

package org.stakeaclaim.protection.managers;

import java.util.List;
import java.util.Map;

import com.sk89q.worldedit.Vector;
import org.stakeaclaim.LocalPlayer;
import org.stakeaclaim.protection.ApplicableRequestSet;
import org.stakeaclaim.protection.databases.ProtectionDatabaseException;
import org.stakeaclaim.protection.databases.ProtectionDatabase;
import org.stakeaclaim.protection.requests.ProtectedRequest;

/**
 * An abstract class for getting, setting, and looking up requests. The most
 * simple implementation uses a flat list and iterates through the entire list
 * to look for applicable requests, but a more complicated (and more efficient)
 * implementation may use space partitioning techniques.
 *
 * @author sk89q
 */
public abstract class RequestManager {

    protected ProtectionDatabase loader;

    /**
     * Construct the object.
     *
     * @param loader The loader for this request
     */
    public RequestManager(ProtectionDatabase loader) {
        this.loader = loader;
    }

    /**
     * Load the list of requests. If the requests do not load properly, then
     * the existing list should be used (as stored previously).
     *
     * @throws ProtectionDatabaseException when an error occurs
     */
    public void load() throws ProtectionDatabaseException {
        loader.load(this);
    }

    /**
     * Save the list of requests.
     *
     * @throws ProtectionDatabaseException when an error occurs while saving
     */
    public void save() throws ProtectionDatabaseException {
        loader.save(this);
    }

    /**
     * Get a map of protected requests. Use one of the request manager methods
     * if possible if working with requests.
     *
     * @return map of requests, with keys being request IDs (lowercase)
     */
    public abstract Map<String, ProtectedRequest> getRequests();

    /**
     * Set a list of protected requests. Keys should be lowercase in the given
     * map fo requests.
     *
     * @param requests map of requests
     */
    public abstract void setRequests(Map<String, ProtectedRequest> requests);

    /**
     * Adds a request. If a request by the given name already exists, then
     * the existing request will be replaced.
     *
     * @param request request to add
     */
    public abstract void addRequest(ProtectedRequest request);

    /**
     * Return whether a request exists by an ID.
     *
     * @param id id of the request, can be mixed-case
     * @return whether the request exists
     */
    public abstract boolean hasRequest(String id);

    /**
     * Get a request by its ID. Includes symbolic names like #&lt;index&gt;
     *
     * @param id id of the request, can be mixed-case
     * @return request or null if it doesn't exist
     */
    public ProtectedRequest getRequest(String id) {
        if (id.startsWith("#")) {
            int index;
            try {
                index = Integer.parseInt(id.substring(1)) - 1;
            } catch (NumberFormatException e) {
                return null;
            }
            for (ProtectedRequest request : getRequests().values()) {
                if (index == 0) {
                    return request;
                }
                --index;
            }
            return null;
        }

        return getRequestExact(id);
    }

    /**
     * Get a request by its ID.
     *
     * @param id id of the request, can be mixed-case
     * @return request or null if it doesn't exist
     */
    public ProtectedRequest getRequestExact(String id) {
        return getRequests().get(id.toLowerCase());
    }

    /**
     * Removes a request, including inheriting children.
     *
     * @param id id of the request, can be mixed-case
     */
    public abstract void removeRequest(String id);

    /**
     * Get an object for a point for rules to be applied with. Use this in order
     * to query for flag data or membership data for a given point.
     *
     * @param loc Bukkit location
     * @return applicable request set
     */
    public ApplicableRequestSet getApplicableRequests(org.bukkit.Location loc) {
        return getApplicableRequests(com.sk89q.worldedit.bukkit.BukkitUtil.toVector(loc));
    }

    /**
     * Get an object for a point for rules to be applied with. Use this in order
     * to query for flag data or membership data for a given point.
     *
     * @param pt point
     * @return applicable request set
     */
    public abstract ApplicableRequestSet getApplicableRequests(Vector pt);

    /**
     * Get an object for a point for rules to be applied with. This gets
     * a set for the given reason.
     *
     * @param request request
     * @return regino set
     */
    public abstract ApplicableRequestSet getApplicableRequests(
            ProtectedRequest request);

    /**
     * Get a list of request IDs that contain a point.
     *
     * @param pt point
     * @return list of request Ids
     */
    public abstract List<String> getApplicableRequestsIDs(Vector pt);

    /**
     * Returns true if the provided request overlaps with any other request that
     * is not owned by the player.
     *
     * @param request request to check
     * @param player player to check against
     * @return whether there is an overlap
     */
    public abstract boolean overlapsUnownedRequest(ProtectedRequest request,
            LocalPlayer player);

    /**
     * Get the number of requests.
     *
     * @return number of requests
     */
    public abstract int size();

    /**
     * Get the number of requests for a player.
     *
     * @param player player
     * @return name number of requests that a player owns
     */
    public abstract int getRequestCountOfPlayer(LocalPlayer player);
}