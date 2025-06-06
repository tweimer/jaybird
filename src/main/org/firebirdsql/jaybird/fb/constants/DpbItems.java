// SPDX-FileCopyrightText: Copyright 2021-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later OR BSD-3-Clause
// SPDX-FileComment: The constants listed here were obtained from the Firebird sources, which are licensed under the IPL (InterBase Public License) and/or IDPL (Initial Developer Public License), both are variants of the Mozilla Public License version 1.1
package org.firebirdsql.jaybird.fb.constants;

/**
 * Constants for DPB (database parameter buffer) items.
 *
 * @author Mark Rotteveel
 * @since 5
 */
@SuppressWarnings({ "unused", "java:S115" })
public final class DpbItems {

    public static final int isc_dpb_cdd_pathname = 1;
    public static final int isc_dpb_allocation = 2;
    public static final int isc_dpb_journal = 3;
    public static final int isc_dpb_page_size = 4;
    public static final int isc_dpb_num_buffers = 5;
    public static final int isc_dpb_buffer_length = 6;
    public static final int isc_dpb_debug = 7;
    public static final int isc_dpb_garbage_collect = 8;
    public static final int isc_dpb_verify = 9;
    public static final int isc_dpb_sweep = 10;
    public static final int isc_dpb_enable_journal = 11;
    public static final int isc_dpb_disable_journal = 12;
    public static final int isc_dpb_dbkey_scope = 13;
    public static final int isc_dpb_number_of_users = 14;
    public static final int isc_dpb_trace = 15;
    public static final int isc_dpb_no_garbage_collect = 16;
    public static final int isc_dpb_damaged = 17;
    public static final int isc_dpb_license = 18;
    public static final int isc_dpb_sys_user_name = 19;
    public static final int isc_dpb_encrypt_key = 20;
    public static final int isc_dpb_activate_shadow = 21;
    public static final int isc_dpb_sweep_interval = 22;
    public static final int isc_dpb_delete_shadow = 23;
    public static final int isc_dpb_force_write = 24;
    public static final int isc_dpb_begin_log = 25;
    public static final int isc_dpb_quit_log = 26;
    public static final int isc_dpb_no_reserve = 27;
    public static final int isc_dpb_user_name = 28;
    public static final int isc_dpb_user = isc_dpb_user_name;
    public static final int isc_dpb_password = 29;
    public static final int isc_dpb_password_enc = 30;
    public static final int isc_dpb_sys_user_name_enc = 31;
    public static final int isc_dpb_interp = 32;
    public static final int isc_dpb_online_dump = 33;
    public static final int isc_dpb_old_file_size = 34;
    public static final int isc_dpb_old_num_files = 35;
    public static final int isc_dpb_old_file = 36;
    public static final int isc_dpb_old_start_page = 37;
    public static final int isc_dpb_old_start_seqno = 38;
    public static final int isc_dpb_old_start_file = 39;
    public static final int isc_dpb_drop_walfile = 40;
    public static final int isc_dpb_old_dump_id = 41;
    public static final int isc_dpb_wal_backup_dir = 42;
    public static final int isc_dpb_wal_chkptlen = 43;
    public static final int isc_dpb_wal_numbufs = 44;
    public static final int isc_dpb_wal_bufsize = 45;
    public static final int isc_dpb_wal_grp_cmt_wait = 46;
    public static final int isc_dpb_lc_messages = 47;
    public static final int isc_dpb_lc_ctype = 48;
    public static final int isc_dpb_cache_manager = 49;
    public static final int isc_dpb_shutdown = 50;
    public static final int isc_dpb_online = 51;
    public static final int isc_dpb_shutdown_delay = 52;
    public static final int isc_dpb_reserved = 53;
    public static final int isc_dpb_overwrite = 54;
    public static final int isc_dpb_sec_attach = 55;
    public static final int isc_dpb_disable_wal = 56;
    public static final int isc_dpb_connect_timeout = 57;
    public static final int isc_dpb_dummy_packet_interval = 58;
    public static final int isc_dpb_gbak_attach = 59;
    public static final int isc_dpb_sql_role_name = 60;
    public static final int isc_dpb_set_page_buffers = 61;
    public static final int isc_dpb_working_directory = 62;
    public static final int isc_dpb_sql_dialect = 63;
    public static final int isc_dpb_set_db_readonly = 64;
    public static final int isc_dpb_set_db_sql_dialect = 65;
    public static final int isc_dpb_gfix_attach = 66;
    public static final int isc_dpb_gstat_attach = 67;
    public static final int isc_dpb_set_db_charset = 68;

    // Firebird 2.1 constants
    public static final int isc_dpb_gsec_attach = 69;
    public static final int isc_dpb_address_path = 70;
    public static final int isc_dpb_process_id = 71;
    public static final int isc_dpb_no_db_triggers = 72;
    public static final int isc_dpb_trusted_auth = 73;
    public static final int isc_dpb_process_name = 74;

    // Firebird 2.5 constants
    public static final int isc_dpb_trusted_role = 75;
    public static final int isc_dpb_org_filename = 76;
    public static final int isc_dpb_utf8_filename = 77;
    public static final int isc_dpb_ext_call_depth = 78;

    // Firebird 3.0 constants
    public static final int isc_dpb_auth_block = 79;
    public static final int isc_dpb_client_version = 80;
    public static final int isc_dpb_remote_protocol = 81;
    public static final int isc_dpb_host_name = 82;
    public static final int isc_dpb_os_user = 83;
    public static final int isc_dpb_specific_auth_data = 84;
    public static final int isc_dpb_auth_plugin_list = 85;
    public static final int isc_dpb_auth_plugin_name = 86;
    public static final int isc_dpb_config = 87;
    public static final int isc_dpb_nolinger = 88;
    public static final int isc_dpb_reset_icu = 89;
    public static final int isc_dpb_map_attach = 90;

    // Firebird 4 constants
    public static final int isc_dpb_session_time_zone = 91;
    public static final int isc_dpb_set_db_replica = 92;
    public static final int isc_dpb_set_bind = 93;
    public static final int isc_dpb_decfloat_round = 94;
    public static final int isc_dpb_decfloat_traps = 95;
    public static final int isc_dpb_clear_map = 96;

    // Firebird 5 constants
    public static final int isc_dpb_parallel_workers = 100;
    public static final int isc_dpb_worker_attach = 101;

    private DpbItems() {
        // no instances
    }
}
