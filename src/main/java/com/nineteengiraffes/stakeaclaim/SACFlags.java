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

import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.EnumFlag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StringFlag;

public final class SACFlags {

    public enum Status {
        PENDING,
        ACCEPTED,
        DENIED,
        CANCELED
    }

    public static final BooleanFlag RECLAIMED = new BooleanFlag("reclaimed");
    public static final BooleanFlag PENDING = new BooleanFlag("pending-claim");
    public static final BooleanFlag VIP = new BooleanFlag("vip-claim");
    public static final StringFlag REQUEST_NAME = new StringFlag("request-name");
    public static final EnumFlag<Status> REQUEST_STATUS = new EnumFlag<Status>("request-status", Status.class);
    public static final StateFlag ENTRY_DEFAULT = new StateFlag("entry-default",true);
    public static final StringFlag CLAIM_NAME = new StringFlag("claim-name");

}
