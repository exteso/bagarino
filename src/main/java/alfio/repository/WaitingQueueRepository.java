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
package alfio.repository;

import alfio.model.WaitingQueueSubscription;
import ch.digitalfondue.npjt.*;

import java.time.ZonedDateTime;
import java.util.List;

@QueryRepository
public interface WaitingQueueRepository {

    @Query("select * from waiting_queue where event_id = :eventId and status = 'WAITING' order by creation")
    List<WaitingQueueSubscription> loadAllWaiting(@Bind("eventId") int eventId);

    @Query("select * from waiting_queue where event_id = :eventId order by creation")
    List<WaitingQueueSubscription> loadAll(@Bind("eventId") int eventId);

    @Query("select * from waiting_queue where id = :id")
    WaitingQueueSubscription loadById(@Bind("id") int id);

    @Query("select * from waiting_queue where event_id = :eventId and status = 'WAITING' order by creation asc limit :max for update")
    List<WaitingQueueSubscription> loadWaiting(@Bind("eventId") int eventId, @Bind("max") int maxNumber);

    @Query("insert into waiting_queue (event_id, full_name, first_name, last_name, email_address, creation, status, user_language, subscription_type, selected_category_id) values(:eventId, :fullName, :firstName, :lastName, :emailAddress, :creation, 'WAITING', :userLanguage, :subscriptionType, :selectedCategoryId)")
    @AutoGeneratedKey("id")
    AffectedRowCountAndKey<Integer> insert(@Bind("eventId") int eventId, @Bind("fullName") String fullName, @Bind("firstName") String firstName, @Bind("lastName") String lastName,  @Bind("emailAddress") String emailAddress, @Bind("creation") ZonedDateTime creation, @Bind("userLanguage") String userLanguage, @Bind("subscriptionType") WaitingQueueSubscription.Type subscriptionType, @Bind("selectedCategoryId") Integer selectedCategoryId);

    @Query("update waiting_queue set status = :status where ticket_reservation_id = :reservationId")
    int updateStatusByReservationId(@Bind("reservationId") String reservationId, @Bind("status") String status);

    @Query("update waiting_queue set status = 'EXPIRED', ticket_reservation_id = null where ticket_reservation_id in (:ticketReservationIds)")
    int bulkUpdateExpiredReservations(@Bind("ticketReservationIds") List<String> ticketReservationIds);

    @Query("select count(*) from waiting_queue where event_id = :eventId and status = 'WAITING'")
    Integer countWaitingPeople(@Bind("eventId") int eventId);

    @Query("update waiting_queue set ticket_reservation_id = :ticketReservationId, status = 'PENDING' where id = :id")
    int flagAsPending(@Bind("ticketReservationId") String ticketReservationId, @Bind("id") int id);

    @Query("update waiting_queue set status = :newStatus where id = :id and status = :expectedStatus")
    int updateStatus(@Bind("id") int id, @Bind("newStatus") WaitingQueueSubscription.Status newStatus, @Bind("expectedStatus") WaitingQueueSubscription.Status expectedStatus);
}
