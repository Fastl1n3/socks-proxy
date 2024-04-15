package com.burym;

import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import org.xbill.DNS.lookup.LookupResult;
import org.xbill.DNS.lookup.LookupSession;


import java.net.UnknownHostException;
import java.util.Arrays;

import java.util.concurrent.CompletableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class ResolvingDNS {

    public static Future<LookupResult> lookupAddr(String address) throws TextParseException, UnknownHostException, ExecutionException, InterruptedException {
        LookupSession lookupSession = LookupSession.defaultBuilder().build();
        CompletableFuture<LookupResult> lookupResultCompletionStage = lookupSession.lookupAsync(new Name(address), Type.A, DClass.IN).toCompletableFuture();
        return lookupResultCompletionStage;
        //        LookupResult lookupResult = lookupResultCompletionStage.get();
//        for (Record record : lookupResult.getRecords()) {
//            System.out.println(Arrays.toString(record.rdataToWireCanonical()));
//            System.out.println(record.rdataToString());
//            return record.rdataToWireCanonical();
//        }
//        return null;
//        Record queryRecord = Record.newRecord(Name.fromString(address), Type.A, DClass.IN);
//        Message queryMessage = Message.newQuery(queryRecord);
//        Resolver r = new SimpleResolver("8.8.8.8");
//        Message mes =
//                r.sendAsync(queryMessage).whenComplete(
//                (answer, ex) -> {
//                    if (ex == null) {
//                        System.out.println("DNS " + answer);
//                    }
//                    else {
//                        System.out.println(ex.getMessage());
//                    }
//                }
//        ).toCompletableFuture().get();
    }
}
