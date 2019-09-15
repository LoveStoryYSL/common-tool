package org.xbill.DNS;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnknownHostException;
import java.util.*;

/**
 * 改造Lookup{@link org.xbill.DNS.Lookup}类，去掉Cache，在不需要cache的业务下留着cache只会消耗性能
 * @author : Vince
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class NoCacheLookup {

    private Resolver resolver;
    private static final Name[] noAliases = new Name[0];

    private boolean verbose = Options.check("verbose");
    private static final int DEFAULT_TYPE = Type.A;

    private int dclass = 1;
    private int type;
    private Name name;
    private int iterations;
    private boolean done;
    private List<Name> aliases;
    private Record[] answers;
    private int result = -1;
    private String error;
    private boolean nxdomain;
    private boolean badresponse;
    private String badresponse_error;
    private boolean timedout;
    private boolean networkerror;
    private boolean nametoolong;
    private boolean referral;

    private void reset() {
        this.dclass = 1;
        this.type = DEFAULT_TYPE;
        this.name = null;
        this.iterations = 0;
        this.done = false;
        this.aliases = null;
        this.answers = null;
        this.result = -1;
        this.error = null;
        this.nxdomain = false;
        this.badresponse = false;
        this.badresponse_error = null;
        this.timedout = false;
        this.networkerror = false;
        this.nametoolong = false;
        this.referral = false;
    }

    @SuppressWarnings("Duplicates")
    private static Set<String> getDefaultDnsServers() {
        String[] servers = ResolverConfig.getCurrentConfig().servers();
        Set<String> list = new HashSet<>();
        if (servers != null) {
            for (String server : servers) {
                if (server != null) {
                    list.add(server.toLowerCase());
                }
            }
        }
        return list;
    }

    private Resolver getDefaultResolver() throws UnknownHostException {
        Set<String> defaultDnsServers = getDefaultDnsServers();
        return new DynamicRoutingResolver(new ArrayList<>(defaultDnsServers));
    }

    public Resolver getResolver() throws UnknownHostException {
        if (this.resolver == null) {
            this.resolver = getDefaultResolver();
        }
        return this.resolver;
    }

    public NoCacheLookup(String[] dnsServers) throws UnknownHostException {
        this(DEFAULT_TYPE, new DynamicRoutingResolver(new ArrayList<>(Arrays.asList(dnsServers))));
    }

    @SuppressWarnings("WeakerAccess")
    public NoCacheLookup(int type, Resolver resolver) {
        Type.check(type);
        if (!Type.isRR(type) && type != 255) {
            throw new IllegalArgumentException("Cannot query for meta-types other than ANY");
        } else {
            this.type = type;
        }
        this.resolver = resolver;
    }

    public NoCacheLookup(Resolver resolver) {
        this(DEFAULT_TYPE, resolver);
    }

    @SuppressWarnings("unused")
    public NoCacheLookup() {
        this(DEFAULT_TYPE, null);
    }

    public void setType(int type) {
        Type.check(type);
        if (!Type.isRR(type) && type != 255) {
            throw new IllegalArgumentException("Cannot query for meta-types other than ANY");
        } else {
            this.type = type;
        }
    }

    public void setResolver(Resolver resolver) {
        if (resolver != null) {
            this.resolver = resolver;
        }
    }
//    @SuppressWarnings("unused")
//    public NoCacheLookup(String name) throws TextParseException, UnknownHostException {
//        this(Name.fromString(name), 1, null);
//    }

    private void follow(Name name, Name oldname) {
        this.badresponse = false;
        this.networkerror = false;
        this.timedout = false;
        this.nxdomain = false;
        this.referral = false;
        ++this.iterations;
        if (this.iterations < 10 && !name.equals(oldname)) {
            if (this.aliases == null) {
                this.aliases = new ArrayList<>();
            }

            this.aliases.add(oldname);
            this.lookup(name);
        } else {
            this.result = 1;
            this.error = "CNAME loop";
            this.done = true;
        }
    }

    @SuppressWarnings("RedundantCast")
    private void processResponse(Name name, SetResponse response) {
        if (response == null) {
            return;
        }
        if (response.isSuccessful()) {
            RRset[] rrsets = response.answers();
            List<Object> l = new ArrayList<>();

            for (RRset rrset : rrsets) {
                Iterator it = rrset.rrs();

                while (it.hasNext()) {
                    l.add(it.next());
                }
            }

            this.result = 0;
            this.answers = (Record[]) l.toArray(new Record[0]);
            this.done = true;
        } else if (response.isNXDOMAIN()) {
            this.nxdomain = true;
            if (this.iterations > 0) {
                this.result = 3;
                this.done = true;
            }
        } else if (response.isNXRRSET()) {
            this.result = 4;
            this.answers = null;
            this.done = true;
        } else if (response.isCNAME()) {
            CNAMERecord cname = response.getCNAME();
            this.follow(cname.getTarget(), name);
        } else if (response.isDNAME()) {
            DNAMERecord dname = response.getDNAME();

            try {
                this.follow(name.fromDNAME(dname), name);
            } catch (NameTooLongException var7) {
                this.result = 1;
                this.error = "Invalid DNAME target";
                this.done = true;
            }
        } else if (response.isDelegation()) {
            this.referral = true;
        }

    }

    private SetResponse getSetResponse(Message in) {
        Record question = in.getQuestion();
        int rcode = in.getHeader().getRcode();

        if ((rcode != 0 && rcode != 3) || question == null) {
            return null;
        }

        boolean completed = false;
        SetResponse response = null;
        Name qname = question.getName();
        int qtype = question.getType();
        int qclass = question.getDClass();
        Name curname = qname;
        RRset[] answers = in.getSectionRRsets(1);

        for (RRset answer : answers) {
            if (answer.getDClass() != qclass) {
                continue;
            }

            int type = answer.getType();
            Name name = answer.getName();

            if ((type == qtype || qtype == 255) && name.equals(curname)) {
                completed = true;
                if (curname == qname) {
                    if (response == null) {
                        response = new SetResponse(6);
                    }
                    response.addRRset(answer);
                }

            } else if (type == 5 && name.equals(curname)) {
                if (curname == qname) {
                    response = new SetResponse(4, answer);
                }
                CNAMERecord cname = (CNAMERecord) answer.first();
                curname = cname.getTarget();

            } else if (type == 39 && curname.subdomain(name)) {
                if (curname == qname) {
                    response = new SetResponse(5, answer);
                }

                DNAMERecord dname = (DNAMERecord) answer.first();

                try {
                    curname = curname.fromDNAME(dname);
                } catch (NameTooLongException var22) {
                    break;
                }
            }
        }
        if (completed) return response;


        RRset[] auth = in.getSectionRRsets(2);
        RRset soa = null;
        RRset ns = null;

        for (RRset rRset : auth) {
            if (rRset.getType() == 6 && curname.subdomain(rRset.getName())) {
                soa = rRset;
            } else if (rRset.getType() == 2 && curname.subdomain(rRset.getName())) {
                ns = rRset;
            }
        }

        if (rcode != 3 && soa == null && ns != null) {
            if (response == null) {
                response = new SetResponse(3, ns);
            }
        } else {
            if (response == null) {
                byte responseType;
                if (rcode == 3) {
                    responseType = 1;
                } else {
                    responseType = 2;
                }
                response = SetResponse.ofType(responseType);
            }
        }
        return response;
    }

    private void lookup(Name current) {
        Record question = Record.newRecord(current, this.type, this.dclass);
        Message query = Message.newQuery(question);
        Message response;

        try {
            response = this.getResolver().send(query);
        } catch (IOException var7) {
            if (var7 instanceof InterruptedIOException) {
                this.timedout = true;
            } else {
                this.networkerror = true;
            }
            return;
        }

        int rcode = response.getHeader().getRcode();
        if (rcode != 0 && rcode != 3) {
            this.badresponse = true;
            this.badresponse_error = Rcode.string(rcode);
        } else if (!query.getQuestion().equals(response.getQuestion())) {
            this.badresponse = true;
            this.badresponse_error = "response does not match query";
        } else {
            if (this.verbose) {
                System.err.println("queried " + current + " " + Type.string(this.type));
                System.err.println(response);
            }

            SetResponse sr = getSetResponse(response);
            this.processResponse(current, sr);
        }
    }

    private void resolve(Name current, Name suffix) {
        Name tname;
        if (suffix == null) {
            tname = current;
        } else {
            try {
                tname = Name.concatenate(current, suffix);
            } catch (NameTooLongException var5) {
                this.nametoolong = true;
                return;
            }
        }

        this.lookup(tname);
    }

    public Record[] run(String domain) {
        return run(domain,DEFAULT_TYPE);
    }

    public Record[] run(String domain, int type) {
        try {
            Name name = Name.fromString(domain);
            return run(name,type);
        } catch (TextParseException e) {
            return new Record[0];
        }
    }

    public Record[] run(Name name, int type) {
        if (this.done) {
            this.reset();
        }
        this.name = name;
        this.type = type;

        if (name.isAbsolute()) {
            this.resolve(name, null);
        } else {
            this.resolve(name, Name.root);
        }

        if (!this.done) {
            if (this.badresponse) {
                this.result = 2;
                this.error = this.badresponse_error;
                this.done = true;
            } else if (this.timedout) {
                this.result = 2;
                this.error = "timed out";
                this.done = true;
            } else if (this.networkerror) {
                this.result = 2;
                this.error = "network error";
                this.done = true;
            } else if (this.nxdomain) {
                this.result = 3;
                this.done = true;
            } else if (this.referral) {
                this.result = 1;
                this.error = "referral";
                this.done = true;
            } else if (this.nametoolong) {
                this.result = 1;
                this.error = "name too long";
                this.done = true;
            }
        }

        return this.answers;
    }

    private void checkDone() {
        if (!this.done || this.result == -1) {
            StringBuilder sb = new StringBuilder("Lookup of " + this.name + " ");
            if (this.dclass != 1) {
                sb.append(DClass.string(this.dclass)).append(" ");
            }

            sb.append(Type.string(this.type)).append(" isn't done");
            throw new IllegalStateException(sb.toString());
        }
    }

    public Record[] getAnswers() {
        this.checkDone();
        return this.answers;
    }

    public Name[] getAliases() {
        this.checkDone();
        return this.aliases == null ? noAliases : this.aliases.toArray(new Name[0]);
    }

    public int getResult() {
        this.checkDone();
        return this.result;
    }

    public String getErrorString() {
        this.checkDone();
        if (this.error != null) {
            return this.error;
        } else {
            switch (this.result) {
                case 0:
                    return "successful";
                case 1:
                    return "unrecoverable error";
                case 2:
                    return "try again";
                case 3:
                    return "host not found";
                case 4:
                    return "type not found";
                default:
                    throw new IllegalStateException("unknown result");
            }
        }
    }

}
