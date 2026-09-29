/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

/** Modular send-only APRS transport boundary. */
public interface AprsTransport {
    SendResult send(AprsTrackingConfig config, String packet);

    final class SendResult {
        public enum Status {
            SUCCESS,
            RETRYABLE_FAILURE,
            UNCERTAIN_DELIVERY,
            PERMANENT_FAILURE
        }

        private final Status status;
        private final String detail;

        private SendResult(Status status, String detail) {
            this.status = status;
            this.detail = detail;
        }

        public static SendResult success() {
            return new SendResult(Status.SUCCESS, "sent");
        }

        public static SendResult retryable(String detail) {
            return new SendResult(Status.RETRYABLE_FAILURE, detail);
        }

        public static SendResult uncertain(String detail) {
            return new SendResult(Status.UNCERTAIN_DELIVERY, detail);
        }

        public static SendResult permanent(String detail) {
            return new SendResult(Status.PERMANENT_FAILURE, detail);
        }

        public Status getStatus() {
            return status;
        }

        public String getDetail() {
            return detail;
        }
    }
}
