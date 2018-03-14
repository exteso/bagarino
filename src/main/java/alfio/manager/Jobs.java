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
package alfio.manager;

import alfio.config.Initializer;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.user.UserManager;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.model.user.User;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
@AllArgsConstructor
public class Jobs {

    private static final int ONE_MINUTE = 1000 * 60;
    private static final int THIRTY_MINUTES = 30 * ONE_MINUTE;
    private static final int THIRTY_SECONDS = 1000 * 30;
    private static final int FIVE_SECONDS = 1000 * 5;

    private final TicketReservationManager ticketReservationManager;
    private final NotificationManager notificationManager;
    private final SpecialPriceTokenGenerator specialPriceTokenGenerator;
    private final FileUploadManager fileUploadManager;
    private final WaitingQueueSubscriptionProcessor waitingQueueSubscriptionProcessor;
    private final Environment environment;
    private final UserManager userManager;
    private final EventManager eventManager;
    private final ConfigurationManager configurationManager;
    private final AdminReservationRequestManager adminReservationRequestManager;

    void cleanupExpiredPendingReservation() {
        //cleanup reservation that have a expiration older than "now minus 10 minutes": this give some additional slack.
        final Date expirationDate = DateUtils.addMinutes(new Date(), -10);
        ticketReservationManager.cleanupExpiredReservations(expirationDate);
        ticketReservationManager.cleanupExpiredOfflineReservations(expirationDate);
        ticketReservationManager.markExpiredInPaymentReservationAsStuck(expirationDate);
    }

    void sendOfflinePaymentReminder() {
        ticketReservationManager.sendReminderForOfflinePayments();
    }

    void sendOfflinePaymentReminderToEventOrganizers() {
        ticketReservationManager.sendReminderForOfflinePaymentsToEventManagers();
    }

    void sendTicketAssignmentReminder() {
        ticketReservationManager.sendReminderForTicketAssignment();
        ticketReservationManager.sendReminderForOptionalData();
    }

    void generateSpecialPriceCodes() {
        specialPriceTokenGenerator.generatePendingCodes();
    }

    void sendEmails() {
        notificationManager.sendWaitingMessages();
    }

    void processReleasedTickets() {
        waitingQueueSubscriptionProcessor.handleWaitingTickets();
    }

    void cleanupUnreferencedBlobFiles() {
        fileUploadManager.cleanupUnreferencedBlobFiles();
    }

    void cleanupForDemoMode() {
        if(environment.acceptsProfiles(Initializer.PROFILE_DEMO)) {
            int expirationDate = configurationManager.getIntConfigValue(Configuration.getSystemConfiguration(ConfigurationKeys.DEMO_MODE_ACCOUNT_EXPIRATION_DAYS), 20);
            List<Integer> userIds = userManager.disableAccountsOlderThan(DateUtils.addDays(new Date(), -expirationDate), User.Type.DEMO);
            if(!userIds.isEmpty()) {
                eventManager.disableEventsFromUsers(userIds);
            }
        }
    }

    Pair<Integer, Integer> processReservationRequests() {
        return adminReservationRequestManager.processPendingReservations();
    }

    @DisallowConcurrentExecution
    @Log4j2
    public static class SendOfflinePaymentReminderToEventOrganizers implements Job {

        //run each hour
        public static String CRON_EXPRESSION = "0 0 0/1 1/1 * ? *";

        //run each minute
        //public static String CRON_EXPRESSION = "0 0/1 * 1/1 * ? *";

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            jobs.sendOfflinePaymentReminderToEventOrganizers();
            log.trace("running job " + getClass().getSimpleName());
        }
    }


    @DisallowConcurrentExecution
    @Log4j2
    public static class CleanupForDemoMode implements Job {
        //run each hour
        public static String CRON_EXPRESSION = "0 0 0/1 1/1 * ? *";

        //run each minute
        //public static String CRON_EXPRESSION = "0 0/1 * 1/1 * ? *";

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            jobs.cleanupForDemoMode();
            log.trace("running job " + getClass().getSimpleName());
        }

    }



    @DisallowConcurrentExecution
    @Log4j2
    public static class CleanupExpiredPendingReservation implements Job {

        public static long INTERVAL = THIRTY_SECONDS;

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            log.trace("running job " + getClass().getSimpleName());
            jobs.cleanupExpiredPendingReservation();
        }
    }

    @DisallowConcurrentExecution
    @Log4j2
    public static class SendOfflinePaymentReminder implements  Job {

        public static long INTERVAL = THIRTY_MINUTES;

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            log.trace("running job " + getClass().getSimpleName());
            jobs.sendOfflinePaymentReminder();
        }
    }

    @DisallowConcurrentExecution
    @Log4j2
    public static class SendTicketAssignmentReminder implements  Job {

        public static long INTERVAL = THIRTY_MINUTES;

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            log.trace("running job " + getClass().getSimpleName());
            jobs.sendTicketAssignmentReminder();
        }
    }


    @DisallowConcurrentExecution
    @Log4j2
    public static class GenerateSpecialPriceCodes implements  Job {

        public static long INTERVAL = THIRTY_SECONDS;

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            log.trace("running job " + getClass().getSimpleName());
            jobs.generateSpecialPriceCodes();
        }
    }

    @DisallowConcurrentExecution
    @Log4j2
    public static class SendEmails implements Job {

        public static long INTERVAL = FIVE_SECONDS;

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            log.trace("running job " + getClass().getSimpleName());
            jobs.sendEmails();
        }
    }

    @DisallowConcurrentExecution
    @Log4j2
    public static class ProcessReservationRequests implements Job {

        public static long INTERVAL = FIVE_SECONDS;

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            log.trace("running job " + getClass().getSimpleName());
            long start = System.currentTimeMillis();
            Pair<Integer, Integer> result = jobs.processReservationRequests();
            if(result.getLeft() > 0 || result.getRight() > 0) {
                log.info("ProcessReservationRequests: got {} success and {} failures. Elapsed {} ms", result.getLeft(), result.getRight(), System.currentTimeMillis() - start);
            }
        }
    }

    @DisallowConcurrentExecution
    @Log4j2
    public static class ProcessReleasedTickets implements Job {

        public static long INTERVAL = THIRTY_SECONDS;

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            log.trace("running job " + getClass().getSimpleName());
            jobs.processReleasedTickets();
        }
    }

    @DisallowConcurrentExecution
    @Log4j2
    public static class CleanupUnreferencedBlobFiles implements Job {

        public static long INTERVAL = ONE_MINUTE * 60;

        @Autowired
        private Jobs jobs;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            log.trace("running job " + getClass().getSimpleName());
            jobs.cleanupUnreferencedBlobFiles();
        }
    }

}
