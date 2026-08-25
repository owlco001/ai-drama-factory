Subject: Re: Service suspension – abuse report (UCEPROTECT-Level1) – Consent to reinstall + IP change request

Dear Mr. Pandian / RackNerd Support Team,

Thank you for your response. I agree to the operating system reinstallation to resolve the abuse report, and I understand that all existing data on the server will be erased.

Before proceeding, I have two requests:

1. **IP address change**: Since IP 107.173.70.115 is already listed on UCEPROTECT-Level1, reinstalling the OS on the same IP would leave the blacklist issue unresolved — the listing will persist and affect any mail or outbound services I run. Could you please assign a **different IP address** as part of the reinstall? If a free replacement is not possible, please let me know the cost of an IP change so I can decide.

2. **Reinstallation details**: Please confirm which OS images are available (I would like Debian 12 minimal). Once the reinstall is done and the new IP is assigned, kindly send me the new credentials.

I have identified the likely source of the abusive activity (an outbound mail service and a proxy panel that were insufficiently hardened). After the reinstall, I will:
- Keep outbound port 25 blocked by default;
- Restrict SSH to key-based authentication only;
- Set up firewall rules allowing only required inbound ports.

I apologize for the inconvenience this has caused, and I appreciate your assistance in getting the service restored securely.

Best regards,
[你的名字]
Service: racknerd-3591cdd
