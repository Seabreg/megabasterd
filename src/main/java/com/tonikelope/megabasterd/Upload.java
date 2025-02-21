package com.tonikelope.megabasterd;

import static com.tonikelope.megabasterd.MainPanel.*;
import static com.tonikelope.megabasterd.MiscTools.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import static java.lang.Integer.MAX_VALUE;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JComponent;

/**
 *
 * @author tonikelope
 */
public final class Upload implements Transference, Runnable, SecureSingleThreadNotifiable {

    public static final int WORKERS_DEFAULT = 6;
    public static final int CHUNK_SIZE_MULTI = 1; //Otra cosa da errores al reanudar una subida (investigar)
    private final MainPanel _main_panel;
    private volatile UploadView _view;
    private volatile ProgressMeter _progress_meter;
    private final Object _progress_lock;
    private String _status_error_message;
    private volatile boolean _exit;
    private volatile boolean _frozen;
    private int _slots;
    private final Object _secure_notify_lock;
    private final Object _workers_lock;
    private final Object _chunkid_lock;
    private byte[] _byte_file_key;
    private volatile long _progress;
    private byte[] _byte_file_iv;
    private final ConcurrentLinkedQueue<Long> _rejectedChunkIds;
    private long _last_chunk_id_dispatched;
    private final ConcurrentLinkedQueue<Long> _partialProgressQueue;
    private final ExecutorService _thread_pool;
    private int[] _file_meta_mac;
    private int[] _file_temp_mac;
    private boolean _finishing_upload;
    private String _fid;
    private boolean _notified;
    private volatile String _completion_handle;
    private int _paused_workers;
    private Double _progress_bar_rate;
    private volatile boolean _pause;
    private final ArrayList<ChunkUploader> _chunkworkers;
    private long _file_size;
    private UploadMACGenerator _mac_generator;
    private boolean _create_dir;
    private boolean _provision_ok;
    private boolean _status_error;
    private String _file_link;
    private final MegaAPI _ma;
    private final String _file_name;
    private final String _parent_node;
    private int[] _ul_key;
    private String _ul_url;
    private final String _root_node;
    private final byte[] _share_key;
    private final String _folder_link;
    private final boolean _restart;
    private volatile boolean _closed;
    private volatile boolean _canceled;

    public Upload(MainPanel main_panel, MegaAPI ma, String filename, String parent_node, int[] ul_key, String ul_url, String root_node, byte[] share_key, String folder_link) {

        _notified = false;
        _frozen = main_panel.isInit_paused();
        _provision_ok = true;
        _status_error = false;
        _canceled = false;
        _closed = false;
        _main_panel = main_panel;
        _ma = ma;
        _file_name = filename;
        _parent_node = parent_node;
        _ul_key = ul_key;
        _ul_url = ul_url;
        _root_node = root_node;
        _share_key = share_key;
        _folder_link = folder_link;
        _restart = false;
        _progress = 0L;
        _last_chunk_id_dispatched = 0L;
        _completion_handle = null;
        _secure_notify_lock = new Object();
        _workers_lock = new Object();
        _chunkid_lock = new Object();
        _chunkworkers = new ArrayList<>();
        _progress_lock = new Object();
        _partialProgressQueue = new ConcurrentLinkedQueue<>();
        _rejectedChunkIds = new ConcurrentLinkedQueue<>();
        _thread_pool = Executors.newCachedThreadPool();
        _view = new UploadView(this);
        _progress_meter = new ProgressMeter(this);
        _file_meta_mac = null;
        _file_temp_mac = null;
    }

    public Upload(Upload upload) {

        _notified = false;
        _provision_ok = true;
        _status_error = false;
        _canceled = false;
        _closed = false;
        _restart = true;
        _main_panel = upload.getMain_panel();
        _ma = upload.getMa();
        _file_name = upload.getFile_name();
        _parent_node = upload.getParent_node();
        _progress_lock = new Object();
        _ul_key = null;
        _ul_url = null;
        _root_node = upload.getRoot_node();
        _share_key = upload.getShare_key();
        _folder_link = upload.getFolder_link();
        _progress = 0L;
        _last_chunk_id_dispatched = 0L;
        _completion_handle = null;
        _secure_notify_lock = new Object();
        _workers_lock = new Object();
        _chunkid_lock = new Object();
        _chunkworkers = new ArrayList<>();
        _partialProgressQueue = new ConcurrentLinkedQueue<>();
        _rejectedChunkIds = new ConcurrentLinkedQueue<>();
        _thread_pool = Executors.newCachedThreadPool();
        _view = new UploadView(this);
        _progress_meter = new ProgressMeter(this);
        _file_meta_mac = null;
        _file_temp_mac = null;
    }

    public Object getWorkers_lock() {
        return _workers_lock;
    }

    public boolean isExit() {
        return _exit;
    }

    public int getSlots() {
        return _slots;
    }

    public Object getSecure_notify_lock() {
        return _secure_notify_lock;
    }

    public byte[] getByte_file_key() {
        return _byte_file_key;
    }

    public int[] getFile_temp_mac() {
        return _file_temp_mac;
    }

    public void setFile_temp_mac(int[] file_temp_mac) {
        _file_temp_mac = file_temp_mac;
    }

    @Override
    public long getProgress() {
        return _progress;
    }

    public byte[] getByte_file_iv() {
        return _byte_file_iv;
    }

    public ConcurrentLinkedQueue<Long> getRejectedChunkIds() {
        return _rejectedChunkIds;
    }

    public long getLast_chunk_id_dispatched() {
        return _last_chunk_id_dispatched;
    }

    public ExecutorService getThread_pool() {
        return _thread_pool;
    }

    public String getFid() {
        return _fid;
    }

    public boolean isNotified() {
        return _notified;
    }

    public String getCompletion_handle() {
        return _completion_handle;
    }

    public int getPaused_workers() {
        return _paused_workers;
    }

    public Double getProgress_bar_rate() {
        return _progress_bar_rate;
    }

    public boolean isPause() {
        return _pause;
    }

    public ArrayList<ChunkUploader> getChunkworkers() {

        synchronized (_workers_lock) {
            return _chunkworkers;
        }

    }

    @Override
    public long getFile_size() {
        return _file_size;
    }

    public UploadMACGenerator getMac_generator() {
        return _mac_generator;
    }

    public boolean isCreate_dir() {
        return _create_dir;
    }

    public boolean isProvision_ok() {
        return _provision_ok;
    }

    public boolean isStatus_error() {
        return _status_error;
    }

    public String getFile_link() {
        return _file_link;
    }

    public MegaAPI getMa() {
        return _ma;
    }

    @Override
    public String getFile_name() {
        return _file_name;
    }

    public String getParent_node() {
        return _parent_node;
    }

    public int[] getUl_key() {
        return _ul_key;
    }

    public String getUl_url() {
        return _ul_url;
    }

    public String getRoot_node() {
        return _root_node;
    }

    public byte[] getShare_key() {
        return _share_key;
    }

    public String getFolder_link() {
        return _folder_link;
    }

    public boolean isRestart() {
        return _restart;
    }

    public void setCompletion_handle(String completion_handle) {
        _completion_handle = completion_handle;
    }

    public void setFile_meta_mac(int[] file_meta_mac) {
        _file_meta_mac = file_meta_mac;
    }

    public void setPaused_workers(int paused_workers) {
        _paused_workers = paused_workers;
    }

    @Override
    public ProgressMeter getProgress_meter() {

        while (_progress_meter == null) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }

        return _progress_meter;
    }

    @Override
    public UploadView getView() {

        while (_view == null) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }

        return this._view;
    }

    @Override
    public void secureNotify() {
        synchronized (_secure_notify_lock) {

            _notified = true;

            _secure_notify_lock.notify();
        }
    }

    @Override
    public void secureWait() {

        synchronized (_secure_notify_lock) {
            while (!_notified) {

                try {
                    _secure_notify_lock.wait();
                } catch (InterruptedException ex) {
                    _exit = true;
                    LOG.log(Level.SEVERE, null, ex);
                }
            }

            _notified = false;
        }
    }

    public void provisionIt() {

        getView().printStatusNormal("Provisioning upload, please wait...");

        File the_file = new File(_file_name);

        _provision_ok = false;

        if (!the_file.exists()) {

            _status_error_message = "ERROR: FILE NOT FOUND";

        } else {

            try {
                _file_size = the_file.length();

                HashMap upload_progress = DBTools.selectUploadProgress(getFile_name(), getMa().getFull_email());

                if (upload_progress == null) {

                    if (_ul_key == null) {

                        _ul_key = _ma.genUploadKey();

                        DBTools.insertUpload(_file_name, _ma.getFull_email(), _parent_node, Bin2BASE64(i32a2bin(_ul_key)), _root_node, Bin2BASE64(_share_key), _folder_link);
                    }

                    _provision_ok = true;

                } else {

                    _last_chunk_id_dispatched = calculateLastUploadedChunk((long) upload_progress.get("bytes_uploaded"));

                    _progress = (long) upload_progress.get("bytes_uploaded");

                    _provision_ok = true;

                    LOG.log(Level.INFO, "LAST CHUNK ID UPLOADED -> {0}", _last_chunk_id_dispatched);
                }

            } catch (SQLException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }

        if (!_provision_ok) {

            _status_error = true;

            if (_file_name != null) {
                swingInvoke(
                        new Runnable() {
                    @Override
                    public void run() {

                        getView().getFile_name_label().setVisible(true);

                        getView().getFile_name_label().setText(_file_name);

                        getView().getFile_name_label().setText(truncateText(_file_name, 100));

                        getView().getFile_name_label().setToolTipText(_file_name);

                        getView().getFile_size_label().setVisible(true);

                        getView().getFile_size_label().setText(formatBytes(_file_size));
                    }
                });
            }

            getView().hideAllExceptStatus();

            if (_status_error_message == null) {

                _status_error_message = "PROVISION FAILED";
            }

            getView().printStatusError(_status_error_message);

            swingInvoke(
                    new Runnable() {
                @Override
                public void run() {

                    getView().getRestart_button().setVisible(true);
                }
            });

        } else {

            getView().printStatusNormal(LabelTranslatorSingleton.getInstance().translate(_frozen ? "(FROZEN) Waiting to start (" : "Waiting to start (") + _ma.getFull_email() + ") ...");

            swingInvoke(
                    new Runnable() {
                @Override
                public void run() {

                    getView().getFile_name_label().setVisible(true);

                    getView().getFile_name_label().setText(_file_name);

                    getView().getFile_name_label().setText(truncateText(_file_name, 100));

                    getView().getFile_name_label().setToolTipText(_file_name);

                    getView().getFile_size_label().setVisible(true);

                    getView().getFile_size_label().setText(formatBytes(_file_size));
                }
            });

        }

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getView().getClose_button().setVisible(true);
                getView().getQueue_down_button().setVisible(true);
                getView().getQueue_up_button().setVisible(true);
            }
        });

    }

    @Override
    public void start() {

        THREAD_POOL.execute(this);
    }

    @Override
    public void stop() {
        if (!isExit()) {
            getMain_panel().getUpload_manager().setPaused_all(false);
            stopUploader();
        }
    }

    @Override
    public void pause() {

        if (isPaused()) {

            setPause(false);

            getMain_panel().getUpload_manager().setPaused_all(false);

            setPaused_workers(0);

            synchronized (_workers_lock) {

                for (ChunkUploader uploader : getChunkworkers()) {

                    uploader.secureNotify();
                }
            }

            getView().resume();

        } else {

            setPause(true);

            getView().pause();
        }

        getMain_panel().getUpload_manager().secureNotify();
    }

    @Override
    public void restart() {

        Upload new_upload = new Upload(this);

        getMain_panel().getUpload_manager().getTransference_remove_queue().add(this);

        getMain_panel().getUpload_manager().getTransference_provision_queue().add(new_upload);

        getMain_panel().getUpload_manager().secureNotify();
    }

    @Override
    public void close() {

        _closed = true;

        getMain_panel().getUpload_manager().getTransference_remove_queue().add(this);

        getMain_panel().getUpload_manager().secureNotify();
    }

    @Override
    public boolean isPaused() {
        return isPause();
    }

    @Override
    public boolean isStopped() {
        return isExit();
    }

    @Override
    public void checkSlotsAndWorkers() {

        if (!isExit()) {

            synchronized (_workers_lock) {

                int sl = getView().getSlots();

                int cworkers = getChunkworkers().size();

                if (sl != cworkers) {

                    if (sl > cworkers) {

                        startSlot();

                    } else {

                        stopLastStartedSlot();

                    }
                }
            }

        }
    }

    @Override
    public ConcurrentLinkedQueue<Long> getPartialProgress() {
        return _partialProgressQueue;
    }

    @Override
    public MainPanel getMain_panel() {
        return _main_panel;
    }

    public void startSlot() {

        if (!_exit) {

            synchronized (_workers_lock) {

                int chunkthiser_id = _chunkworkers.size() + 1;

                ChunkUploader c = new ChunkUploader(chunkthiser_id, this);

                _chunkworkers.add(c);

                try {

                    LOG.log(Level.INFO, "{0} Starting chunkuploader from startslot()...", Thread.currentThread().getName());

                    _thread_pool.execute(c);

                } catch (java.util.concurrent.RejectedExecutionException e) {
                    LOG.log(Level.INFO, e.getMessage());
                }

            }

        }
    }

    public void setPause(boolean pause) {
        _pause = pause;
    }

    public void stopLastStartedSlot() {

        if (!_exit) {

            synchronized (_workers_lock) {

                if (!_chunkworkers.isEmpty()) {

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {

                            getView().getSlots_spinner().setEnabled(false);
                        }
                    });

                    int i = _chunkworkers.size() - 1;

                    while (i >= 0) {

                        ChunkUploader chunkuploader = _chunkworkers.get(i);

                        if (!chunkuploader.isExit()) {

                            chunkuploader.setExit(true);

                            chunkuploader.secureNotify();

                            _view.updateSlotsStatus();

                            break;

                        } else {

                            i--;
                        }
                    }
                }

            }

        }
    }

    public void rejectChunkId(long chunk_id) {
        _rejectedChunkIds.add(chunk_id);
    }

    @Override
    public void run() {

        LOG.log(Level.INFO, "{0} Uploader hello! {1}", new Object[]{Thread.currentThread().getName(), this.getFile_name()});

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getView().getQueue_down_button().setVisible(false);
                getView().getQueue_up_button().setVisible(false);
            }
        });

        getView().printStatusNormal("Starting upload, please wait...");

        if (!_exit) {

            if (_ul_url == null || _restart) {

                int conta_error = 0;

                do {
                    _ul_url = _ma.initUploadFile(_file_name);

                    if (_ul_url == null && !_exit) {

                        long wait_time = MiscTools.getWaitTimeExpBackOff(++conta_error);

                        LOG.log(Level.INFO, "{0} Uploader {1} Upload URL is null, retrying in {2} secs...", new Object[]{Thread.currentThread().getName(), this.getFile_name(), wait_time});

                        try {

                            Thread.sleep(wait_time * 1000);

                        } catch (InterruptedException ex) {

                            LOG.log(Level.SEVERE, null, ex);
                        }
                    }

                } while (_ul_url == null && !_exit);

                if (_ul_url != null) {

                    try {

                        DBTools.updateUploadUrl(_file_name, _ma.getFull_email(), _ul_url);

                    } catch (SQLException ex) {
                        LOG.log(Level.SEVERE, null, ex);
                    }
                }
            }

            if (!_exit && _ul_url != null) {

                int[] file_iv = {_ul_key[4], _ul_key[5], 0, 0};

                _byte_file_key = i32a2bin(Arrays.copyOfRange(_ul_key, 0, 4));

                _byte_file_iv = i32a2bin(file_iv);

                swingInvoke(
                        new Runnable() {
                    @Override
                    public void run() {

                        getView().getClose_button().setVisible(false);
                    }
                });

                if (_file_size > 0) {

                    _progress_bar_rate = Integer.MAX_VALUE / (double) _file_size;

                    getView().updateProgressBar(0);

                } else {

                    getView().updateProgressBar(MAX_VALUE);
                }

                _thread_pool.execute(getProgress_meter());

                getMain_panel().getGlobal_up_speed().attachTransference(this);

                _mac_generator = new UploadMACGenerator(this);

                _thread_pool.execute(_mac_generator);

                synchronized (_workers_lock) {

                    _slots = getMain_panel().getDefault_slots_up();

                    _view.getSlots_spinner().setValue(_slots);

                    for (int t = 1; t <= _slots; t++) {
                        ChunkUploader c = new ChunkUploader(t, this);

                        _chunkworkers.add(c);

                        LOG.log(Level.INFO, "{0} Starting chunkuploader {1} ...", new Object[]{Thread.currentThread().getName(), t});

                        _thread_pool.execute(c);
                    }

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {

                            getView().getSlots_label().setVisible(true);

                            getView().getSlots_spinner().setVisible(true);

                            getView().getSlot_status_label().setVisible(true);
                        }
                    });

                }

                getView().printStatusNormal(LabelTranslatorSingleton.getInstance().translate("Uploading file to mega (") + _ma.getFull_email() + ") ...");

                swingInvoke(
                        new Runnable() {
                    @Override
                    public void run() {

                        getView().getPause_button().setVisible(true);

                        getView().getProgress_pbar().setVisible(true);
                    }
                });

                secureWait();

                _thread_pool.shutdown();

                LOG.log(Level.INFO, "{0} Chunkuploaders finished! {1}", new Object[]{Thread.currentThread().getName(), this.getFile_name()});

                getProgress_meter().setExit(true);

                getProgress_meter().secureNotify();

                try {

                    LOG.log(Level.INFO, "{0}Waiting for all threads to finish {1}...", new Object[]{Thread.currentThread().getName(), this.getFile_name()});

                    _thread_pool.awaitTermination(MAX_WAIT_WORKERS_SHUTDOWN, TimeUnit.SECONDS);

                } catch (InterruptedException ex) {
                    LOG.log(Level.SEVERE, null, ex);
                }

                if (!_thread_pool.isTerminated()) {

                    LOG.log(Level.INFO, "{0} Closing thread pool in ''mecag\u00fcen'' style {1}...", new Object[]{Thread.currentThread().getName(), this.getFile_name()});

                    _thread_pool.shutdownNow();
                }

                LOG.log(Level.INFO, "{0} Uploader thread pool finished! {1}", new Object[]{Thread.currentThread().getName(), this.getFile_name()});

                getMain_panel().getGlobal_up_speed().detachTransference(this);

                swingInvoke(
                        new Runnable() {
                    @Override
                    public void run() {

                        for (JComponent c : new JComponent[]{getView().getSpeed_label(), getView().getPause_button(), getView().getStop_button(), getView().getSlots_label(), getView().getSlots_spinner()}) {
                            c.setVisible(false);
                        }
                    }
                });

                if (!_exit) {

                    if (_completion_handle != null) {

                        LOG.log(Level.INFO, "{0} Uploader creating NEW MEGA NODE {1}...", new Object[]{Thread.currentThread().getName(), this.getFile_name()});

                        getView().printStatusNormal("Creating new MEGA node ... ***DO NOT EXIT MEGABASTERD NOW***");

                        File f = new File(_file_name);

                        HashMap<String, Object> upload_res;

                        int[] ul_key = _ul_key;

                        int[] node_key = {ul_key[0] ^ ul_key[4], ul_key[1] ^ ul_key[5], ul_key[2] ^ _file_meta_mac[0], ul_key[3] ^ _file_meta_mac[1], ul_key[4], ul_key[5], _file_meta_mac[0], _file_meta_mac[1]};

                        int conta_error = 0;

                        do {
                            upload_res = _ma.finishUploadFile(f.getName(), ul_key, node_key, _file_meta_mac, _completion_handle, _parent_node, i32a2bin(_ma.getMaster_key()), _root_node, _share_key);

                            if (upload_res == null && !_exit) {

                                long wait_time = MiscTools.getWaitTimeExpBackOff(++conta_error);

                                LOG.log(Level.INFO, "{0} Uploader {1} Finisih upload res is null, retrying in {2} secs...", new Object[]{Thread.currentThread().getName(), this.getFile_name(), wait_time});

                                try {

                                    Thread.sleep(wait_time * 1000);

                                } catch (InterruptedException ex) {

                                    LOG.log(Level.SEVERE, null, ex);
                                }
                            }

                        } while (upload_res == null && !_exit);

                        if (upload_res != null && !_exit) {
                            List files = (List) upload_res.get("f");

                            _fid = (String) ((Map<String, Object>) files.get(0)).get("h");

                            try {

                                _file_link = _ma.getPublicFileLink(_fid, i32a2bin(node_key));

                                swingInvoke(
                                        new Runnable() {
                                    @Override
                                    public void run() {

                                        getView().getFile_link_button().setEnabled(true);
                                    }
                                });

                            } catch (Exception ex) {
                                LOG.log(Level.SEVERE, null, ex);
                            }

                            getView().printStatusOK(LabelTranslatorSingleton.getInstance().translate("File successfully uploaded! (") + _ma.getFull_email() + ")");

                            synchronized (this.getMain_panel().getUpload_manager().getLog_file_lock()) {

                                File upload_log = new File(System.getProperty("user.home") + "/megabasterd_upload_" + _root_node + ".log");

                                if (upload_log.exists()) {

                                    FileWriter fr;
                                    try {
                                        fr = new FileWriter(upload_log, true);
                                        fr.write(_file_name + "   [" + MiscTools.formatBytes(_file_size) + "]   " + _file_link + "\n");
                                        fr.close();
                                    } catch (IOException ex) {
                                        Logger.getLogger(Upload.class.getName()).log(Level.SEVERE, null, ex);
                                    }

                                }
                            }
                        }

                    } else {

                        getView().hideAllExceptStatus();

                        getView().printStatusError(_status_error_message != null ? _status_error_message : "UPLOAD FAILED! (Empty completion handle!)");

                        _status_error = true;
                    }

                } else {

                    _canceled = true;

                    getView().hideAllExceptStatus();

                    getView().printStatusNormal("Upload CANCELED!");
                }

            } else {

                _canceled = true;

                getView().hideAllExceptStatus();

                getView().printStatusNormal("Upload CANCELED!");
            }

        } else {

            _canceled = true;

            getView().hideAllExceptStatus();

            getView().printStatusNormal("Upload CANCELED!");
        }

        if (!_status_error || _main_panel.isExit()) {

            try {
                DBTools.deleteUpload(_file_name, _ma.getFull_email());
            } catch (SQLException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }

        getMain_panel().getUpload_manager().getTransference_running_list().remove(this);

        getMain_panel().getUpload_manager().getTransference_finished_queue().add(this);

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getMain_panel().getUpload_manager().getScroll_panel().remove(getView());

                getMain_panel().getUpload_manager().getScroll_panel().add(getView());

                getMain_panel().getUpload_manager().secureNotify();

            }
        });

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getView().getClose_button().setVisible(true);

                if (!_status_error && !_canceled) {

                    getView().getClose_button().setIcon(new javax.swing.ImageIcon(getClass().getResource("/images/icons8-ok-30.png")));

                }

                if (_canceled) {

                    getView().getRestart_button().setVisible(true);
                }

                if (_status_error) {

                    getView().getRestart_button().setEnabled(false);
                }
            }
        });

        THREAD_POOL.execute(
                new Runnable() {
            @Override
            public void run() {

                if (_status_error) {

                    for (int i = 3; !_closed && i > 0; i--) {

                        final int j = i;

                        swingInvoke(
                                new Runnable() {

                            @Override
                            public void run() {
                                getView().getRestart_button().setText("Restart (" + String.valueOf(j) + " secs...)");
                            }
                        });

                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {
                            Logger.getLogger(Upload.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }

                    if (!_closed) {
                        restart();
                    }
                }
            }
        });

        getMain_panel().getUpload_manager().getFinishing_uploads_queue().remove(this);

        LOG.log(Level.INFO, "{0} Uploader {1} BYE BYE", new Object[]{Thread.currentThread().getName(), this.getFile_name()});
    }

    public void pause_worker() {

        synchronized (_workers_lock) {

            if (++_paused_workers >= _chunkworkers.size() && !_exit) {

                getView().printStatusNormal("Upload paused!");

                swingInvoke(
                        new Runnable() {
                    @Override
                    public void run() {
                        getView().getPause_button().setText(LabelTranslatorSingleton.getInstance().translate("RESUME UPLOAD"));
                        getView().getPause_button().setEnabled(true);

                    }
                });

            }
        }

    }

    public void pause_worker_mono() {

        getView().printStatusNormal("Upload paused!");

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getView().getPause_button().setText(LabelTranslatorSingleton.getInstance().translate("RESUME UPLOAD"));
                getView().getPause_button().setEnabled(true);
            }
        });

    }

    public void stopThisSlot(ChunkUploader chunkuploader) {

        synchronized (_workers_lock) {

            if (_chunkworkers.remove(chunkuploader) && !_exit) {

                if (!chunkuploader.isExit()) {

                    _finishing_upload = true;

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {
                            getView().getSlots_spinner().setEnabled(false);

                            getView().getSlots_spinner().setValue((int) getView().getSlots_spinner().getValue() - 1);
                        }
                    });

                } else if (!_finishing_upload) {

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {
                            getView().getSlots_spinner().setEnabled(true);
                        }
                    });

                }

                if (!_exit && _pause && _paused_workers == _chunkworkers.size()) {

                    getView().printStatusNormal("Upload paused!");

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {
                            getView().getPause_button().setText(LabelTranslatorSingleton.getInstance().translate("RESUME UPLOAD"));
                            getView().getPause_button().setEnabled(true);
                        }
                    });

                }

                getView().updateSlotsStatus();

            }
        }
    }

    public long nextChunkId() throws ChunkInvalidException {

        synchronized (_chunkid_lock) {

            Long next_id;

            if ((next_id = _rejectedChunkIds.poll()) != null) {
                return next_id;
            } else {
                return ++_last_chunk_id_dispatched;
            }
        }
    }

    public void setExit(boolean exit) {
        _exit = exit;
    }

    public void setStatus_error(boolean status_error) {
        _status_error = status_error;
    }

    public void stopUploader() {

        if (!_exit) {

            _exit = true;

            getView().stop("Stopping upload, please wait...");

            synchronized (_workers_lock) {

                for (ChunkUploader uploader : _chunkworkers) {

                    uploader.secureNotify();
                }
            }

            secureNotify();
        }
    }

    public void stopUploader(String reason) {

        _status_error = true;

        _status_error_message = (reason != null ? LabelTranslatorSingleton.getInstance().translate("FATAL ERROR! ") + reason : LabelTranslatorSingleton.getInstance().translate("FATAL ERROR! "));

        stopUploader();
    }

    public int[] getFile_meta_mac() {
        return _file_meta_mac;
    }

    @Override
    public void setProgress(long progress) {

        synchronized (_progress_lock) {

            long old_progress = _progress;

            _progress = progress;

            swingInvoke(
                    new Runnable() {
                @Override
                public void run() {

                    getView().updateProgressBar(_progress, _progress_bar_rate);
                }
            });

            getMain_panel().getUpload_manager().increment_total_progress(_progress - old_progress);

        }
    }

    @Override
    public boolean isStatusError() {
        return _status_error;
    }

    public long calculateLastUploadedChunk(long bytes_read) {

        if (bytes_read > 3584 * 1024) {
            return 7 + (long) Math.floor((float) (bytes_read - 3584 * 1024) / (1024 * 1024 * Upload.CHUNK_SIZE_MULTI));
        } else {
            long i = 0, tot = 0;

            while (tot < bytes_read) {
                i++;
                tot += i * 128 * 1024;
            }

            return i;
        }
    }

    public void secureNotifyWorkers() {

        synchronized (_workers_lock) {

            for (ChunkUploader uploader : getChunkworkers()) {

                uploader.secureNotify();
            }
        }
    }

    @Override
    public int getSlotsCount() {
        return getChunkworkers().size();
    }

    @Override
    public boolean isFrozen() {
        return this._frozen;
    }

    @Override
    public void unfreeze() {
        this._frozen = false;
    }

    @Override
    public void upWaitQueue() {
        _main_panel.getUpload_manager().upWaitQueue(this);
    }

    @Override
    public void downWaitQueue() {
        _main_panel.getUpload_manager().downWaitQueue(this);
    }
    private static final Logger LOG = Logger.getLogger(Upload.class.getName());
}
