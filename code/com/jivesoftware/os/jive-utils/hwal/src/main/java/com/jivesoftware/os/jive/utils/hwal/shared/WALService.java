package com.jivesoftware.os.jive.utils.hwal.shared;

import com.jivesoftware.os.jive.utils.base.service.ServiceHandle;

/**
 *
 * @author jonathan.colt
 */
public interface WALService<S> extends ServiceHandle {

    S getService();
}
