/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.model.subscription;

import lombok.Getter;

/**
 * Thrown when a Subscription can be used only a number of times for an event, and the current application
 * would go over that limit.
 */
@Getter
public class SubscriptionUsageExceededForEvent extends RuntimeException {

    public static String ERROR = "ONCE_PER_EVENT_OVERAGE";
    private final int allowed;
    private final int requested;

    public SubscriptionUsageExceededForEvent(int allowed, int requested) {
        this.allowed = allowed;
        this.requested = requested;
    }

}
